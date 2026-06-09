package com.hmdp.service.impl;

import com.hmdp.constant.RedisConstant;
import com.hmdp.entity.Spot;
import com.hmdp.mapper.SpotMapper;
import com.hmdp.mapper.SpotVoucherMapper;
import com.hmdp.service.ISpotToolService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 景点实时数据查询服务实现 — 为 LLM Function Calling 提供实时业务数据。
 *
 * <h3>数据来源</h3>
 * <ul>
 *   <li>门票价格：{@link SpotMapper} 查询 {@code tb_spot.ticket_price}（带 Redis 缓存）</li>
 *   <li>优惠券：{@link SpotVoucherMapper} 查询 {@code tb_voucher} + {@code tb_seckill_voucher}</li>
 *   <li>库存：{@link SpotVoucherMapper} 查询 {@code tb_seckill_voucher.stock}</li>
 * </ul>
 *
 * <h3>缓存策略</h3>
 * <p>景点基本信息通过 Redis 缓存（key: {@code cache:spot:{id}}），
 * 先查缓存，未命中再查数据库并回写缓存。TTL 为 {@link RedisConstant#CACHE_SPOT_TTL} + 随机 0~5 分钟。
 * 优惠券和库存为实时数据，不缓存。</p>
 *
 * <h3>输出格式</h3>
 * <p>所有方法返回结构化文本（非 JSON），LLM 可直接理解并融入回答。</p>
 */
@Slf4j
@Service
public class SpotToolServiceImpl implements ISpotToolService {

    @Resource
    private SpotMapper spotMapper;

    @Resource
    private SpotVoucherMapper spotVoucherMapper;

    @Resource
    private RedisTemplate<Object, Object> redisTemplate;

    @Override
    public String getSpotPrice(Long spotId) {
        if (spotId == null) {
            return "错误：景点 ID 不能为空";
        }

        Spot spot = getSpotWithCache(spotId);
        if (spot == null) {
            return "未找到 ID 为 " + spotId + " 的景点信息";
        }

        Long priceInCents = spot.getTicketPrice();
        if (priceInCents == null) {
            return String.format("景点【%s】暂无门票价格信息", spot.getName());
        }

        // ticketPrice 以分为单位存储，转换为元
        BigDecimal priceInYuan = BigDecimal.valueOf(priceInCents)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        return String.format("景点【%s】当前门票价格为 %.2f 元", spot.getName(), priceInYuan);
    }

    @Override
    public String getSpotVouchers(Long spotId) {
        if (spotId == null) {
            return "错误：景点 ID 不能为空";
        }

        // 先确认景点存在（走缓存）
        Spot spot = getSpotWithCache(spotId);
        if (spot == null) {
            return "未找到 ID 为 " + spotId + " 的景点信息";
        }

        List<Map<String, Object>> vouchers = spotVoucherMapper.queryVouchersBySpotId(spotId);

        if (vouchers == null || vouchers.isEmpty()) {
            return String.format("景点【%s】当前没有可用的优惠券或折扣活动", spot.getName());
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("景点【%s】当前有以下优惠：\n", spot.getName()));

        for (int i = 0; i < vouchers.size(); i++) {
            Map<String, Object> v = vouchers.get(i);
            sb.append(String.format("%d. %s\n", i + 1, v.getOrDefault("title", "未知")));

            Object payValue = v.get("payValue");
            Object actualValue = v.get("actualValue");
            if (payValue != null && actualValue != null) {
                sb.append(String.format("   原价：%s 元，优惠价：%s 元\n", payValue, actualValue));
            }

            Object stock = v.get("stock");
            if (stock != null) {
                sb.append(String.format("   剩余库存：%s 份\n", stock));
            }

            Object rules = v.get("rules");
            if (rules != null && !rules.toString().isEmpty()) {
                sb.append(String.format("   使用规则：%s\n", rules));
            }

            Object beginTime = v.get("beginTime");
            Object endTime = v.get("endTime");
            if (beginTime != null && endTime != null) {
                sb.append(String.format("   有效期：%s 至 %s\n", beginTime, endTime));
            }
        }

        return sb.toString().trim();
    }

    @Override
    public String checkVoucherStock(Long voucherId) {
        if (voucherId == null) {
            return "错误：优惠券 ID 不能为空";
        }

        Map<String, Object> stockInfo = spotVoucherMapper.queryStockByVoucherId(voucherId);

        if (stockInfo == null || stockInfo.isEmpty()) {
            return "未找到 ID 为 " + voucherId + " 的优惠券信息，该优惠券可能已下架或不存在";
        }

        String title = (String) stockInfo.getOrDefault("title", "未知");
        Object stock = stockInfo.get("stock");
        int remaining = stock != null ? ((Number) stock).intValue() : 0;

        if (remaining <= 0) {
            return String.format("优惠券【%s】（ID:%d）已售罄，库存为 0", title, voucherId);
        }

        return String.format("优惠券【%s】（ID:%d）当前剩余库存：%d 份", title, voucherId, remaining);
    }

    // ==================== 缓存辅助方法 ====================

    /**
     * 带 Redis 缓存的景点查询 — 先查缓存，未命中再查数据库并回写。
     *
     * <p>缓存 key: {@code cache:spot:{id}}（复用 {@link RedisConstant#CACHE_SPOT_KEY}）。
     * TTL: {@link RedisConstant#CACHE_SPOT_TTL} + 随机 0~5 分钟，防止缓存雪崩。</p>
     *
     * @param spotId 景点 ID
     * @return Spot 实体，不存在返回 null
     */
    private Spot getSpotWithCache(Long spotId) {
        String cacheKey = RedisConstant.CACHE_SPOT_KEY + spotId;

        // 1. 查 Redis 缓存
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof Spot spot && spot.getId() != null) {
            log.debug("Spot {} 命中缓存", spotId);
            return spot;
        }

        // 2. 缓存未命中，查数据库
        Spot spot = spotMapper.selectById(spotId);
        if (spot == null) {
            return null;
        }

        // 3. 回写缓存（随机 TTL 防止雪崩）
        long ttl = RedisConstant.CACHE_SPOT_TTL + ThreadLocalRandom.current().nextInt(0, 5);
        redisTemplate.opsForValue().set(cacheKey, spot, ttl, TimeUnit.MINUTES);
        log.debug("Spot {} 缓存回写，TTL={}min", spotId, ttl);

        return spot;
    }
}
