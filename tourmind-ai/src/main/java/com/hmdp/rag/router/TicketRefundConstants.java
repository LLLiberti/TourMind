package com.hmdp.rag.router;

import java.util.Set;

/**
 * 购退票关键词常量 — QueryRouter 和 HybridDocumentRetriever 的唯一来源。
 *
 * <p>新增关键词只需修改此文件，分流与检索行为自动保持一致。</p>
 */
public final class TicketRefundConstants {

    /** 触发购退票知识库检索的关键词 */
    public static final Set<String> KEYWORDS = Set.of(
            "退票", "退款", "取消", "退订", "购票", "买票", "预订", "怎么买",
            "门票怎么", "票价", "购票须知", "退票条件", "退票政策", "购买门票",
            "预订门票", "网上购票", "在线购票", "订票", "售票"
    );

    private TicketRefundConstants() {}
}
