package com.hmdp.rag.query;

import com.hmdp.rag.RetrievalContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 查询改写变换器 — 支持多种改写策略。
 *
 * <h3>改写策略</h3>
 * <ul>
 *   <li><b>KEYWORD</b> — 将口语 query 转为 10-20 个关键词串（现有能力，适合简单事实查询）</li>
 *   <li><b>MULTI_QUERY</b> — 从多视角生成 3-5 个改写查询，各自检索后合并（适合推荐类）</li>
 *   <li><b>HYDE</b> — 生成假设文档文本，用假设文档的语义去检索（适合对比/抽象查询）</li>
 *   <li><b>MULTI_HYDE</b> — 结合两者：多视角 + HyDE（适合极复杂查询）</li>
 * </ul>
 *
 * <h3>策略选择</h3>
 * <p>策略由 QueryRouter 写入 RetrievalContext.rewriteStrategy 字段，
 * 本类从 ThreadLocal 读取。若未设置则默认 KEYWORD。</p>
 *
 * <h3>在管线中的位置</h3>
 * <pre>
 * CompressionQueryTransformer → RewriteQueryTransformer → HybridDocumentRetriever
 * </pre>
 */
@Slf4j
public class RewriteQueryTransformer implements QueryTransformer {

    // ==================== KEYWORD 模式 ====================

    private static final String KEYWORD_PROMPT = """
        将用户问题转换为用于向量检索的关键词串。
        规则：
        - 提取问题中的核心实体（景点名、地名、类型等）
        - 扩展语义相关的同义词、关联词（如"好玩"→"好玩 推荐 必去 有趣 游览"）
        - 问题中提到的实体名称必须保留在原样
        - 输出仅包含空格分隔的关键词，不要有任何解释和标点
        - 关键词控制在 10-20 个

        用户问题：%s
        输出：""";

    // ==================== MULTI_QUERY 模式 ====================

    private static final String MULTI_QUERY_PROMPT = """
        将用户查询从不同角度改写为 3-5 个独立检索查询，每条占一行。
        规则：
        - 每条查询必须是自包含的完整检索串
        - 涵盖不同视角：实体视角、属性视角、约束视角、场景视角
        - 每条查询 5-15 个关键词，空格分隔
        - 不要编号，不要解释，只输出查询文本，一行一条

        用户问题：%s
        多视角查询：""";

    // ==================== HYDE 模式 ====================

    private static final String HYDE_PROMPT = """
        假设你是一个知识库，请生成一段可能回答以下问题的文档文本。
        规则：
        - 生成一个假设的、理想的文档片段，尽可能详细
        - 包含问题的关键事实、属性、对比信息
        - 50-150 字，使用与知识库相似的陈述语气
        - 只输出文档文本，不要任何前缀或解释

        用户问题：%s
        假设文档：""";

    // ==================== 配置 ====================

    private static final boolean FALLBACK_ON_ERROR = true;
    private static final int MIN_KEYWORD_LENGTH = 3;

    private final ChatModel chatModel;
    private final int minQueryLength;

    /** 最近一次 multi-query 扩展产生的额外查询（供 SearchKnowledgeBaseTool 合并结果） */
    private final ThreadLocal<List<Query>> lastExpandedQueries = new ThreadLocal<>();

    public RewriteQueryTransformer(ChatModel chatModel, int minQueryLength) {
        this.chatModel = chatModel;
        this.minQueryLength = minQueryLength;
    }

    /** 获取最近一次改写产生的扩展查询列表（用于多查询结果合并），读取后清除 */
    public List<Query> getLastExpandedQueries() {
        List<Query> queries = lastExpandedQueries.get();
        lastExpandedQueries.remove();
        return queries;
    }

    // ==================== QueryTransformer 接口 ====================

    @Override
    public Query transform(Query query) {
        String originalText = query.text();
        if (originalText == null || originalText.isBlank()) {
            return query;
        }

        // 读取策略（由 QueryRouter 设置）
        String strategy = getStrategy();

        // 如果 query 本身已经很短且是 KEYWORD 策略，直接透传
        if ("KEYWORD".equals(strategy) && originalText.trim().length() <= minQueryLength) {
            log.debug("Rewrite: query 过短（{} ≤ {}），跳过改写", originalText.length(), minQueryLength);
            return query;
        }

        log.info("Rewrite: 策略={}, 原始 query='{}'", strategy, originalText);

        try {
            return switch (strategy) {
                case "MULTI_QUERY" -> transformMultiQuery(query);
                case "HYDE" -> transformHyde(query);
                case "MULTI_HYDE" -> transformMultiHyde(query);
                default -> transformKeyword(query); // KEYWORD 或未知
            };
        } catch (Exception e) {
            if (FALLBACK_ON_ERROR) {
                log.warn("Rewrite: {} 模式失败，回退 KEYWORD: {}", strategy, e.getMessage());
                return transformKeywordFallback(query);
            }
            throw new RuntimeException("Query rewrite failed", e);
        }
    }

    // ==================== 策略实现 ====================

    /**
     * KEYWORD 模式 — 现有能力：口语 → 关键词串。
     */
    private Query transformKeyword(Query query) {
        String originalText = query.text();
        String rewritten = callLlm(String.format(KEYWORD_PROMPT, originalText));
        if (rewritten == null || rewritten.length() < MIN_KEYWORD_LENGTH) {
            log.debug("Rewrite KEYWORD: 结果过短，保持原 query");
            return query;
        }
        log.info("Rewrite KEYWORD: '{}'", rewritten);
        return query.mutate().text(rewritten).build();
    }

    /**
     * MULTI_QUERY 模式 — 生成多视角改写，主查询用第一个，其余存入 expandedQueries。
     */
    private Query transformMultiQuery(Query query) {
        String originalText = query.text();
        String raw = callLlm(String.format(MULTI_QUERY_PROMPT, originalText));
        if (raw == null || raw.isBlank()) {
            return transformKeywordFallback(query);
        }

        // 解析多行查询
        String[] lines = raw.split("\\n");
        List<String> validQueries = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && trimmed.length() >= MIN_KEYWORD_LENGTH) {
                validQueries.add(trimmed);
            }
        }

        if (validQueries.isEmpty()) {
            return transformKeywordFallback(query);
        }

        // 第一条作为主改写查询
        String primary = validQueries.get(0);
        log.info("Rewrite MULTI_QUERY: 主='{}', 扩展{}条", primary, validQueries.size() - 1);

        // 其余作为扩展查询
        if (validQueries.size() > 1) {
            List<Query> expanded = new ArrayList<>();
            for (int i = 1; i < validQueries.size(); i++) {
                expanded.add(Query.builder().text(validQueries.get(i)).build());
            }
            lastExpandedQueries.set(expanded);
        }

        return query.mutate().text(primary).build();
    }

    /**
     * HYDE 模式 — 生成假设文档，用假设文档文本作为检索 query。
     */
    private Query transformHyde(Query query) {
        String originalText = query.text();
        String hypothetical = callLlm(String.format(HYDE_PROMPT, originalText));
        if (hypothetical == null || hypothetical.length() < MIN_KEYWORD_LENGTH) {
            return transformKeywordFallback(query);
        }
        log.info("Rewrite HYDE: 假设文档长度={}", hypothetical.length());
        return query.mutate().text(hypothetical).build();
    }

    /**
     * MULTI_HYDE 模式 — 先生成假设文档，再拆为多视角。
     * <p>先用 HyDE 生成理想文档，再用 MULTI_QUERY 视角拆分。</p>
     */
    private Query transformMultiHyde(Query query) {
        String originalText = query.text();

        // Step 1: HyDE 生成假设文档
        String hypothetical = callLlm(String.format(HYDE_PROMPT, originalText));
        if (hypothetical == null || hypothetical.length() < MIN_KEYWORD_LENGTH) {
            return transformMultiQuery(query); // 回退到纯 Multi-Query
        }

        // Step 2: 从假设文档生成多视角查询
        String multiFromHyde = callLlm(String.format(MULTI_QUERY_PROMPT, hypothetical));
        if (multiFromHyde == null || multiFromHyde.isBlank()) {
            // 直接用假设文档检索
            log.info("Rewrite MULTI_HYDE: 仅 HyDE，长度={}", hypothetical.length());
            return query.mutate().text(hypothetical).build();
        }

        // 解析多行
        String[] lines = multiFromHyde.split("\\n");
        List<String> validQueries = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && trimmed.length() >= MIN_KEYWORD_LENGTH) {
                validQueries.add(trimmed);
            }
        }

        if (validQueries.isEmpty()) {
            return query.mutate().text(hypothetical).build();
        }

        // 假设文档作为第一条，其他作为扩展
        String primary = hypothetical;
        log.info("Rewrite MULTI_HYDE: HyDE长度={}, 扩展{}条", hypothetical.length(), validQueries.size());

        List<Query> expanded = new ArrayList<>();
        for (String vq : validQueries) {
            expanded.add(Query.builder().text(vq).build());
        }
        lastExpandedQueries.set(expanded);

        return query.mutate().text(primary).build();
    }

    // ==================== 内部方法 ====================

    private String getStrategy() {
        try {
            RetrievalContext ctx = RetrievalContext.current();
            String strategy = ctx.getRewriteStrategy();
            return strategy != null ? strategy : "KEYWORD";
        } catch (Exception e) {
            return "KEYWORD";
        }
    }

    private Query transformKeywordFallback(Query query) {
        try {
            return transformKeyword(query);
        } catch (Exception e) {
            return query;
        }
    }

    private String callLlm(String promptText) {
        SystemMessage systemMsg = new SystemMessage("你是一个搜索查询优化专家。");
        UserMessage userMsg = new UserMessage(promptText);
        Prompt prompt = new Prompt(List.of(systemMsg, userMsg));

        ChatResponse response = chatModel.call(prompt);
        String result = response.getResult().getOutput().getText();

        if (result != null) {
            result = result.trim();
        }
        return result;
    }
}
