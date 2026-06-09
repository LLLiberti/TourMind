package com.hmdp.tool;

import com.hmdp.service.ISpotToolService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 景点实时数据查询工具 — 通过 Spring AI {@link Tool @Tool} 注解暴露给 LLM。
 *
 * <h3>功能</h3>
 * <ul>
 *   <li>{@code getSpotPrice} — 查询景点门票实时价格（RAG 知识库不包含价格）</li>
 *   <li>{@code getSpotVouchers} — 查询景点可用优惠券/折扣</li>
 *   <li>{@code checkVoucherStock} — 查询优惠券实时库存</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <p>在 {@code AIConfig} 中通过 {@code .defaultTools(spotTools)} 注册到 ChatClient。
 * LLM 根据用户问题自动决定是否调用工具。</p>
 */
@Slf4j
@Component
public class SpotTools {

    @Resource
    private ISpotToolService spotToolService;

    /**
     * 获取景点门票实时价格。
     * <p>适用场景：用户询问"西湖门票多少钱？""雷峰塔门票价格"等。</p>
     *
     * @param spotId 景点 ID
     * @return 格式化价格文本
     */
    @Tool(name = "getSpotPrice",
          description = "获取指定景点的门票实时价格。当用户询问门票价格时调用此工具。")
    public String getSpotPrice(
            @ToolParam(required = true,
                       description = "景点ID，整数。如 1 表示 ID=1 的景点") Long spotId) {
        log.info("Tool 调用: getSpotPrice(spotId={})", spotId);
        String result = spotToolService.getSpotPrice(spotId);
        log.info("Tool 返回: {}", result);
        return result;
    }

    /**
     * 获取景点当前可用的优惠券/折扣信息。
     * <p>适用场景：用户询问"有什么优惠？""有没有折扣券？"等。</p>
     *
     * @param spotId 景点 ID
     * @return 格式化优惠券列表文本
     */
    @Tool(name = "getSpotVouchers",
          description = "获取指定景点当前可用的优惠券、折扣活动信息。当用户询问优惠、折扣、代金券时调用。")
    public String getSpotVouchers(
            @ToolParam(required = true,
                       description = "景点ID，整数。如 1 表示 ID=1 的景点") Long spotId) {
        log.info("Tool 调用: getSpotVouchers(spotId={})", spotId);
        String result = spotToolService.getSpotVouchers(spotId);
        log.info("Tool 返回: {}", result);
        return result;
    }

    /**
     * 检查优惠券的实时库存。
     * <p>适用场景：用户询问"这个券还有吗？""库存还剩多少？"等。</p>
     *
     * @param voucherId 优惠券 ID
     * @return 格式化库存文本
     */
    @Tool(name = "checkVoucherStock",
          description = "检查指定优惠券的实时库存余量。当用户询问优惠券是否还有库存时调用。")
    public String checkVoucherStock(
            @ToolParam(required = true,
                       description = "优惠券ID，整数。从 getSpotVouchers 返回的优惠券列表中获取") Long voucherId) {
        log.info("Tool 调用: checkVoucherStock(voucherId={})", voucherId);
        String result = spotToolService.checkVoucherStock(voucherId);
        log.info("Tool 返回: {}", result);
        return result;
    }
}
