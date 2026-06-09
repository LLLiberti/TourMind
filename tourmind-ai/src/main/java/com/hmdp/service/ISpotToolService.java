package com.hmdp.service;

/**
 * 景点实时数据查询服务 — 为 LLM Function Calling 提供业务数据。
 *
 * <h3>与 RAG 的分工</h3>
 * <ul>
 *   <li><b>RAG（知识库）</b>：处理景点介绍、位置、开放时间、评分等静态信息</li>
 *   <li><b>Tool Service（本接口）</b>：处理门票价格、优惠券、库存等实时数据</li>
 * </ul>
 *
 * <h3>返回格式</h3>
 * <p>所有方法返回 LLM 可直接阅读的结构化文本，而非 JSON。</p>
 */
public interface ISpotToolService {

    /**
     * 查询景点门票实时价格。
     *
     * @param spotId 景点 ID
     * @return 格式化价格文本，如 "景点【西湖】当前门票价格为 80.00 元"
     */
    String getSpotPrice(Long spotId);

    /**
     * 查询景点当前可用的优惠券/折扣。
     *
     * @param spotId 景点 ID
     * @return 格式化优惠券列表文本
     */
    String getSpotVouchers(Long spotId);

    /**
     * 查询指定优惠券的实时库存余量。
     *
     * @param voucherId 优惠券 ID
     * @return 格式化库存文本
     */
    String checkVoucherStock(Long voucherId);
}
