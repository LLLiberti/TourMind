package com.hmdp.rag.router;

import java.util.Set;

/**
 * Adaptive 查询分流器 — 根据用户问题类型决定是否走 RAG 检索管线。
 *
 * <h3>分流策略</h3>
 * <ul>
 *   <li><b>CHITCHAT</b> — 闲聊/问候/感谢/告别，跳过 RAG，直接 LLM 回答（省钱省时）</li>
 *   <li><b>TICKET_REFUND</b> — 购票/退票相关，走主 KB + 退购票 KB 双路检索</li>
 *   <li><b>KNOWLEDGE</b> — 默认，走主 KB 完整 RAG 管线</li>
 * </ul>
 *
 * <h3>实现方式</h3>
 * <p>纯规则匹配，零延迟、零额外 API 调用。简单问句用前缀/关键词判断，
 * 复杂查询走默认 KNOWLEDGE 路径。</p>
 */
public class QueryRouter {

    /** 结果分类 */
    public enum Category {
        /** 闲聊/问候 → 跳过 RAG */
        CHITCHAT,
        /** 景点知识查询 → 走完整 RAG */
        KNOWLEDGE,
        /** 购票/退票查询 → 走 RAG + 退购票 KB */
        TICKET_REFUND
    }

    // ==================== 闲聊关键词 ====================

    private static final Set<String> CHITCHAT_PREFIXES = Set.of(
            "你好", "嗨", "哈喽", "hello", "hi", "hey", "早", "晚上好", "下午好",
            "谢谢", "多谢", "感谢", "thank", "3q", "3Q",
            "再见", "拜拜", "bye", "晚安", "明天见",
            "嗯", "哦", "噢", "啊", "额",
            "好的", "好吧", "ok", "OK", "行", "可以", "了解了", "明白",
            "在吗", "在不在", "你是谁", "你叫什么", "你能做什么", "你会什么"
    );

    private static final Set<String> CHITCHAT_EXACT = Set.of(
            "？", "?", "。。。", "...", "哈哈", "呵呵", "嘿嘿"
    );

    // ==================== 购退票关键词（来源：TicketRefundConstants，唯一真相源） ====================

    /**
     * 对用户查询进行分类。
     *
     * @param query 用户输入（去首尾空格后）
     * @return 分类结果，默认 KNOWLEDGE
     */
    public Category classify(String query) {
        if (query == null || query.isBlank()) {
            return Category.CHITCHAT;
        }

        String trimmed = query.trim();

        // ① 购退票 — 关键词优先匹配（"可以退款吗"不应被"可以"误判为闲聊）
        if (isTicketRefund(trimmed)) {
            return Category.TICKET_REFUND;
        }

        // ② 闲聊/问候 — 前缀匹配或完全匹配
        if (isChitchat(trimmed)) {
            return Category.CHITCHAT;
        }

        // ③ 默认：景点知识查询
        return Category.KNOWLEDGE;
    }

    /**
     * 判断是否为闲聊。
     */
    private boolean isChitchat(String query) {
        // 完全匹配（短文本）
        if (query.length() <= 4 && CHITCHAT_EXACT.contains(query)) {
            return true;
        }

        // 前缀匹配
        for (String prefix : CHITCHAT_PREFIXES) {
            if (query.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断是否为购退票相关查询。
     */
    private boolean isTicketRefund(String query) {
        for (String keyword : TicketRefundConstants.KEYWORDS) {
            if (query.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
