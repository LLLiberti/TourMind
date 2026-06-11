package com.hmdp.rag.query;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import java.util.List;

/**
 * 查询重写变换器 — 将用户口语化问题改写为关键词串，提升向量检索召回率。
 *
 * <h3>为什么需要</h3>
 * <p>用户自然语言 query 含大量口语噪音（"呗""推荐一下""有什么好玩的"），
 * 与知识库中结构化 chunk（{@code [景点简介]\n景点名称：西湖...}）embedding 相似度偏低。
 * 通过 LLM 提取核心实体 + 扩展同义词，输出 10-20 个关键词串，
 * 与 chunk 文本的 embedding 匹配度显著提升。</p>
 *
 * <h3>在管线中的位置</h3>
 * <p>位于 {@code CompressionQueryTransformer}（多轮指代消解）<b>之后</b>：</p>
 * <pre>
 * CompressionQueryTransformer → RewriteQueryTransformer → HybridDocumentRetriever
 * </pre>
 * <p>这样确保指代消解（如"第二个景点"→"雷峰塔"）先完成，再对完整查询进行关键词改写。</p>
 *
 * <h3>改写示例</h3>
 * <pre>{@code
 * 输入："西湖有啥好玩的推荐一下呗"
 * 输出："西湖 自然风景区 游览 推荐 好玩 必去 景点 杭州 名胜古迹 观光"
 * }</pre>
 */
@Slf4j
public class RewriteQueryTransformer implements QueryTransformer {

    /** 改写 prompt — 指示 LLM 将口语 query 转为关键词串 */
    private static final String REWRITE_PROMPT = """
        将用户问题转换为用于向量检索的关键词串。
        规则：
        - 提取问题中的核心实体（景点名、地名、类型等）
        - 扩展语义相关的同义词、关联词（如"好玩"→"好玩 推荐 必去 有趣 游览"）
        - 问题中提到的实体名称必须保留在原样
        - 输出仅包含空格分隔的关键词，不要有任何解释和标点
        - 关键词控制在 10-20 个

        用户问题：%s
        输出：""";

    /** 如果 LLM 调用失败，是否使用原始 query 降级（不阻塞检索） */
    private static final boolean FALLBACK_ON_ERROR = true;

    /** 改写后关键词最短长度（低于此值认为改写失败，回退原始 query） */
    private static final int MIN_KEYWORD_LENGTH = 3;

    private final ChatModel chatModel;
    private final int minQueryLength;

    public RewriteQueryTransformer(ChatModel chatModel, int minQueryLength) {
        this.chatModel = chatModel;
        this.minQueryLength = minQueryLength;
    }

    @Override
    public Query transform(Query query) {
        String originalText = query.text();
        if (originalText == null || originalText.isBlank()) {
            return query;
        }

        // 如果 query 本身已经很短（≤ 配置阈值），可能已经是关键词，直接透传
        if (originalText.trim().length() <= minQueryLength) {
            log.debug("Query 过短（{} ≤ {} 字符），跳过改写: {}", originalText.length(), minQueryLength, originalText);
            return query;
        }

        log.info("RewriteQuery: 原始 query = {}", originalText);

        try {
            String rewritten = rewriteToKeywords(originalText);
            if (rewritten == null || rewritten.length() < MIN_KEYWORD_LENGTH) {
                log.warn("改写结果过短，回退原始 query");
                return query;
            }

            log.info("RewriteQuery: 改写结果 = {}", rewritten);
            return query.mutate().text(rewritten).build();

        } catch (Exception e) {
            if (FALLBACK_ON_ERROR) {
                log.warn("Query 改写失败，回退原始 query: {}", e.getMessage());
                return query;
            }
            throw new RuntimeException("Query rewrite failed", e);
        }
    }

    /**
     * 调用 LLM 将用户问题改写为关键词串。
     */
    private String rewriteToKeywords(String question) {
        String promptText = String.format(REWRITE_PROMPT, question);
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
