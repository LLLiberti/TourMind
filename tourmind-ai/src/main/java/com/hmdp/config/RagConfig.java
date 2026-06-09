package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag")
public class RagConfig {
    /**
     * 检索返回的最相似景点数量
     */
    private int topK = 5;

    /**
     * 相似度阈值
     */
    private double similarityThreshold = 0.4;

    /**
     * 最大上下文景点数量
     */
    private int maxContextSpots = 10;

    /**
     * 系统提示词（注入到 ContextualQueryAugmenter）。
     *
     * <p>告知 LLM：静态信息来自 RAG 上下文，实时数据需调用工具获取。</p>
     */
    private String systemPrompt = """
        你是一个景点推荐助手。请根据提供的景点信息回答用户问题。

        【重要规则】
        - 景点介绍、位置、开放时间、评分等静态信息，请基于上下文中的景点资料回答。
        - 当用户询问门票价格、优惠券、折扣、库存等实时数据时，你必须调用工具函数获取最新数据，不要使用上下文中的任何价格信息。
        - 如果工具调用失败或返回空，请如实告知用户当前无相关数据。
        - 如果景点信息不足以回答问题，请说明情况并给出一般性建议。

        回答要简洁、有条理，突出景点的关键信息。
        支持多轮对话，请结合对话历史理解用户的追问。
        """;

    /**
     * 是否允许空上下文（无相关景点时 LLM 仍可通用回答）
     */
    private boolean allowEmptyContext = false;

    // ==================== 混合检索配置 ====================

    /** Parent-Child 索引配置 */
    private ParentChildConfig parentChild = new ParentChildConfig();

    /** Elasticsearch BM25 检索配置 */
    private EsConfig es = new EsConfig();

    /** RRF 排名融合配置 */
    private RrfConfig rrf = new RrfConfig();

    /** 重排配置（可选，需外部 qwen3-rerank 服务） */
    private RerankerConfig reranker = new RerankerConfig();

    // ==================== 嵌套配置类 ====================

    @Data
    public static class ParentChildConfig {
        // 预留扩展，当前无需额外参数
    }

    @Data
    public static class EsConfig {
        /** ES 索引名称 */
        private String indexName = "spot_chunks";
        /** ES BM25 检索 topK */
        private int topK = 20;
    }

    @Data
    public static class RrfConfig {
        /** RRF 平滑常数 k */
        private int rankConstant = 60;
        /** RRF 融合后的 topK */
        private int topK = 20;
    }

    @Data
    public static class RerankerConfig {
        /** 是否启用重排 */
        private boolean enabled = false;
        /** qwen3-rerank API 地址 */
        private String endpoint = "";
        /** 模型名称 */
        private String model = "qwen3-rerank";
        /** 重排 topK */
        private int topK = 10;
        /** 分数阈值：低于此值的候选被过滤 */
        private double scoreThreshold = 0.1;
        /** HTTP 超时（毫秒） */
        private int timeoutMs = 5000;
    }
}
