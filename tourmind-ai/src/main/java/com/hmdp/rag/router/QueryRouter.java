package com.hmdp.rag.router;

import com.hmdp.rag.RetrievalContext;

import java.util.Set;

/**
 * Adaptive 查询分流器 — 语义模式匹配 + 复杂度评估，结果存入 RetrievalContext。
 *
 * <h3>分流策略</h3>
 * <ul>
 *   <li><b>CHITCHAT</b> — 闲聊/问候/感谢/告别，跳过 RAG</li>
 *   <li><b>FACT_LOOKUP</b> — 简单事实查询（评分/时间/地址/是什么），轻量检索</li>
 *   <li><b>RECOMMENDATION</b> — 推荐/发现类（推荐/好玩/适合/附近），完整检索+MMR</li>
 *   <li><b>COMPARISON</b> — 对比类（A vs B / 哪个更 / 区别），多视角检索+HyDE</li>
 *   <li><b>TICKET_REFUND</b> — 购票/退票，主KB + 退购票KB 双路</li>
 *   <li><b>COMPLEX</b> — 复合多意图，Planner 分解后多子任务执行</li>
 * </ul>
 *
 * <h3>复杂度评分（1-5）</h3>
 * <ul>
 *   <li>1 — 极简（单实体 + 单字段查询）</li>
 *   <li>2 — 简单（单实体 + 描述性查询）</li>
 *   <li>3 — 中等（多实体或带约束）</li>
 *   <li>4 — 复杂（多约束 + 对比/推荐）</li>
 *   <li>5 — 极复杂（多独立意图或大量约束）</li>
 * </ul>
 *
 * <h3>副作用</h3>
 * <p>classify() 会将分类和复杂度写入 {@code RetrievalContext.current()}，
 * 供后续管线（改写/检索/Agent）读取。</p>
 */
public class QueryRouter {

    /** 结果分类 */
    public enum Category {
        /** 闲聊/问候 → 跳过 RAG */
        CHITCHAT,
        /** 简单事实查询 → 轻量检索 */
        FACT_LOOKUP,
        /** 推荐/发现 → 完整检索 */
        RECOMMENDATION,
        /** 对比类 → 多视角检索 */
        COMPARISON,
        /** 购票/退票 → 双路检索 */
        TICKET_REFUND,
        /** 默认景点查询（兜底） */
        KNOWLEDGE,
        /** 复合多意图 → Planner 分解 */
        COMPLEX
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

    // ==================== 事实查询模式 ====================

    private static final Set<String> FACT_PATTERNS = Set.of(
            "是什么", "什么是", "在哪里", "哪个是", "什么时候", "几点", "多少分",
            "评分", "开放时间", "营业时间", "地址", "在哪里", "怎么去",
            "电话", "联系方式", "门票多少", "价格多少", "多少钱"
    );

    private static final Set<String> FACT_PREFIXES = Set.of(
            "什么是", "哪个是", "是不是", "有没有", "会不会"
    );

    // ==================== 推荐模式 ====================

    private static final Set<String> RECOMMEND_PATTERNS = Set.of(
            "推荐", "好玩", "值得", "必去", "适合", "热门", "附近", "周边",
            "有什么", "有哪些", "哪些景点", "什么景点", "好玩的",
            "带孩子", "亲子", "情侣", "老年人", "拍照", "打卡",
            "免费", "便宜", "不贵", "性价比"
    );

    // ==================== 对比模式 ====================

    private static final Set<String> COMPARISON_PATTERNS = Set.of(
            "对比", "比较", "区别", "哪个更", "哪个好", "哪个便宜",
            "有什么不同", "有什么差别", "还是", "vs", "VS", "Vs",
            "选哪个", "推荐哪个", "哪个值得"
    );

    // ==================== 复杂度评估模式 ====================

    /** 含这些词表示更复杂（多约束/多意图） */
    private static final Set<String> COMPLEXITY_SIGNALS = Set.of(
            "并且", "而且", "还要", "同时", "另外", "还有", "以及", "和",
            "免费", "评分高", "评分", "距离", "近", "远", "便宜", "贵",
            "亲子", "带孩子", "适合", "交通", "方便", "停车"
    );

    private static final Set<String> MULTI_INTENT_SIGNALS = Set.of(
            "？", "?", "吗", "呢", "吧"
    );

    /**
     * 对用户查询进行分类并评估复杂度，结果写入 RetrievalContext。
     *
     * @param query 用户输入（去首尾空格后）
     * @return 分类结果
     */
    public Category classify(String query) {
        if (query == null || query.isBlank()) {
            store(Category.CHITCHAT, 1);
            return Category.CHITCHAT;
        }

        String trimmed = query.trim();

        // ① 购退票 — 关键词优先匹配
        if (isTicketRefund(trimmed)) {
            int complexity = assessComplexity(trimmed, Category.TICKET_REFUND);
            store(Category.TICKET_REFUND, complexity);
            return Category.TICKET_REFUND;
        }

        // ② 闲聊/问候
        if (isChitchat(trimmed)) {
            store(Category.CHITCHAT, 1);
            return Category.CHITCHAT;
        }

        // ③ 对比类
        if (isComparison(trimmed)) {
            int complexity = assessComplexity(trimmed, Category.COMPARISON);
            store(Category.COMPARISON, complexity);
            return Category.COMPARISON;
        }

        // ④ 复合多意图
        if (isComplexQuery(trimmed)) {
            int complexity = assessComplexity(trimmed, Category.COMPLEX);
            store(Category.COMPLEX, Math.max(complexity, 4));
            return Category.COMPLEX;
        }

        // ⑤ 推荐类
        if (isRecommendation(trimmed)) {
            int complexity = assessComplexity(trimmed, Category.RECOMMENDATION);
            store(Category.RECOMMENDATION, complexity);
            return Category.RECOMMENDATION;
        }

        // ⑥ 简单事实查询
        if (isFactLookup(trimmed)) {
            store(Category.FACT_LOOKUP, assessComplexity(trimmed, Category.FACT_LOOKUP));
            return Category.FACT_LOOKUP;
        }

        // ⑦ 默认：知识查询
        int complexity = assessComplexity(trimmed, Category.KNOWLEDGE);
        store(Category.KNOWLEDGE, complexity);
        return Category.KNOWLEDGE;
    }

    // ==================== 分类判断 ====================

    private boolean isChitchat(String query) {
        if (query.length() <= 4 && CHITCHAT_EXACT.contains(query)) {
            return true;
        }
        for (String prefix : CHITCHAT_PREFIXES) {
            if (query.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTicketRefund(String query) {
        for (String keyword : TicketRefundConstants.KEYWORDS) {
            if (query.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isComparison(String query) {
        int matches = 0;
        for (String pattern : COMPARISON_PATTERNS) {
            if (query.contains(pattern)) {
                matches++;
            }
        }
        // 至少匹配 1 个对比模式 + 提及了多个实体（长度 > 5 作为简单代理）
        return matches >= 1 && query.length() > 5;
    }

    private boolean isComplexQuery(String query) {
        // 多个问号 → 多意图
        long questionCount = query.chars().filter(c -> c == '？' || c == '?').count();
        if (questionCount >= 2) return true;

        // 多个独立意图信号（"并且/还要/同时/另外"等连接词） + 长度足够
        int multiSignals = 0;
        for (String sig : MULTI_INTENT_SIGNALS) {
            if (query.contains(sig)) multiSignals++;
        }
        int connectorCount = 0;
        for (String kw : Set.of("并且", "而且", "还要", "同时", "另外")) {
            if (query.contains(kw)) connectorCount++;
        }
        if (connectorCount >= 2) return true;
        if (connectorCount >= 1 && query.length() > 20) return true;

        return false;
    }

    private boolean isRecommendation(String query) {
        int matches = 0;
        for (String pattern : RECOMMEND_PATTERNS) {
            if (query.contains(pattern)) {
                matches++;
            }
        }
        return matches >= 1;
    }

    private boolean isFactLookup(String query) {
        // 简单事实查询：短查询 + 包含事实模式词
        for (String pattern : FACT_PATTERNS) {
            if (query.contains(pattern)) {
                return true;
            }
        }
        for (String prefix : FACT_PREFIXES) {
            if (query.startsWith(prefix)) {
                return true;
            }
        }
        // 短查询（≤10 字符）+ 包含景点类词汇 → 很可能是事实查询
        if (query.length() <= 10) {
            // 快速判断：是否像一个简单问句
            if (query.contains("的")) {
                return true;
            }
        }
        return false;
    }

    // ==================== 复杂度评估 ====================

    /**
     * 评估查询复杂度（1-5）。
     */
    private int assessComplexity(String query, Category category) {
        int score = 1; // 基础

        // +1：长度 > 15 字符
        if (query.length() > 15) score++;
        // +1：长度 > 30 字符
        if (query.length() > 30) score++;

        // 复杂度信号词计数
        int signalCount = 0;
        for (String sig : COMPLEXITY_SIGNALS) {
            if (query.contains(sig)) signalCount++;
        }
        if (signalCount >= 3) score += 2;
        else if (signalCount >= 1) score += 1;

        // 多实体检测：通过顿号/逗号/空格分隔推测
        if (query.contains("、") || query.contains("和")) score++;

        // 分类固有基线
        switch (category) {
            case COMPARISON -> score = Math.max(score, 3);
            case RECOMMENDATION -> score = Math.max(score, 2);
            case COMPLEX -> score = Math.max(score, 4);
            case FACT_LOOKUP -> score = Math.min(score, 2);
            case CHITCHAT -> score = 1;
            default -> {}
        }

        return Math.min(score, 5);
    }

    // ==================== 上下文存储 ====================

    private void store(Category category, int complexity) {
        RetrievalContext ctx = RetrievalContext.current();
        ctx.setQueryCategory(category.name());
        ctx.setQueryComplexity(complexity);

        // 根据分类绑定改写策略
        ctx.setRewriteStrategy(deriveRewriteStrategy(category, complexity));
    }

    /**
     * 根据分类 + 复杂度推导改写策略。
     */
    static String deriveRewriteStrategy(Category category, int complexity) {
        return switch (category) {
            case CHITCHAT, FACT_LOOKUP -> "KEYWORD";
            case RECOMMENDATION -> complexity >= 4 ? "MULTI_HYDE" : "MULTI_QUERY";
            case COMPARISON -> "HYDE";
            case TICKET_REFUND -> "KEYWORD";
            case COMPLEX -> "MULTI_HYDE";
            default -> complexity >= 3 ? "MULTI_QUERY" : "KEYWORD";
        };
    }
}
