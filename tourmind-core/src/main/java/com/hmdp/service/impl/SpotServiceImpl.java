package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.hash.BloomFilter;
import com.hmdp.dto.Result;
import com.hmdp.entity.Spot;
import com.hmdp.mapper.SpotMapper;
import com.hmdp.service.ISpotService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.SystemConstants;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constant.RedisConstant.*;

/**
 * <p>
 *  服务实现类
 * </p>
 * @author lbq
 */
@Service
public class SpotServiceImpl extends ServiceImpl<SpotMapper, Spot> implements ISpotService {

    @Resource
    private RedisTemplate<Object, Object> redisTemplate;

    @Resource
    private BloomFilter<String> spotBloomFilter;

    @Resource
    private RedissonClient redissonClient;


    private volatile boolean bloomFilterInitialized = false;
    private final Object initLock = new Object();

    @Override
    public Object queryById(Long id) {

        // 1. 布隆过滤器优化：延迟初始化+双重检查锁
        if (!bloomFilterInitialized) {
            synchronized (initLock) {
                if (!bloomFilterInitialized) {
                    initBloomFilter();
                    bloomFilterInitialized = true;
                }
            }
        }
        String cacheKey = CACHE_SPOT_KEY + id;
        // 布隆过滤器判断
        if (!spotBloomFilter.mightContain(cacheKey)) {
            return Result.fail("景点不存在");
        }

        return getSpotWithLock(cacheKey, id);
    }

    @Override
    public Result update(Spot spot) {
        Long id = spot.getId();
        if(id == null){
            return Result.fail("景点id不能为空");
        }
        updateById(spot);
        redisTemplate.delete(CACHE_SPOT_KEY + id);
        return Result.ok();
    }

    @Override
    public Result saveSpot(Spot spot) {
        save(spot);
        spotBloomFilter.put(CACHE_SPOT_KEY + spot.getId());
        return Result.ok(spot.getId());
    }

    private void initBloomFilter() {
        // 批量查询优化：只查询ID字段
        List<Long> ids = getBaseMapper().selectAllIds();
        for (Long id : ids) {
            spotBloomFilter.put(CACHE_SPOT_KEY + id);
        }
    }

    private Result getSpotWithLock(String cacheKey, Long id){
        String spotJson = (String) redisTemplate.opsForValue().get(cacheKey);
        if(StrUtil.isNotBlank(spotJson)){
            //存在
            Spot spot = BeanUtil.toBean(spotJson, Spot.class);
            return Result.ok(spot);
        }

        //空值保护
        if ("".equals(spotJson)) {
            return Result.fail("系统繁忙，请稍后再试！");
        }

        //分布式锁防护缓存击穿
        String lockKey = LOCK_SPOT_KEY + id;
        RLock lock = redissonClient.getLock(lockKey);
        boolean isLock = false;
        try {
            isLock = lock.tryLock(5, TimeUnit.SECONDS);
            if(isLock){
                // 二次检查缓存（double-check）
                    spotJson = (String) redisTemplate.opsForValue().get(cacheKey);
                    if(StrUtil.isNotBlank(spotJson)){
                        Spot spot = BeanUtil.toBean(spotJson, Spot.class);
                        return Result.ok(spot);
                    }

                    Spot spot = getById(id);

                    //缓存穿透
                    if(spot == null){
                        redisTemplate.opsForValue().set(CACHE_SPOT_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return Result.fail("景点不存在");
                    }
                    //随机时间防止缓存雪崩
                    redisTemplate.opsForValue().set(CACHE_SPOT_KEY + id, JSONUtil.toJsonStr(spot),
                            CACHE_SPOT_TTL + ThreadLocalRandom.current().nextInt(0, 30), TimeUnit.MINUTES);
                    return Result.ok(spot);
            }
            else{
                Thread.sleep(50);
                return Result.fail("系统繁忙，请稍后再试！");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }finally {
            if(isLock && lock.isHeldByCurrentThread()){
                lock.unlock();
            }
        }


    }

    @Override
    public Result querySpotByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Spot> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：spotId、distance
        String key = SPOT_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<Object>> results = redisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<Object>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取景点id
            String spotIdStr = result.getContent().getName().toString();
            ids.add(Long.valueOf(spotIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(spotIdStr, distance);
        });
        // 5.根据id查询Spot
        String idStr = StrUtil.join(",", ids);
        List<Spot> spots = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Spot spot : spots) {
            spot.setDistance(distanceMap.get(spot.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(spots);
    }
}
