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
        你是一个景点推荐助手。请严格根据提供的景点信息回答用户问题。

        【核心原则：严格基于上下文】
        - 你只能使用上下文中明确提供的信息来回答，绝对禁止根据自身训练数据编造、补充或推测任何信息。
        - 上下文中的景点信息包含：景点ID、名称、类型、区域、地址、评分、开放时间、距离。
        - 呈现景点时只列出上下文中实际存在的字段，不要自行添加"亮点""特色""推荐理由""游玩建议""历史背景"等任何上下文没有的内容。
        - 如果上下文没有提供某个信息，直接说明"未提供该信息"，不要尝试补充。

        【工具调用规则】
        - 上下文中每个景点都有"景点ID"字段，这是调用工具函数时必须使用的标识。
        - 当用户询问门票价格、优惠券、折扣、库存等实时数据时，你必须调用工具函数获取最新数据：
          * 查询门票价格 → 调用 getSpotPrice，参数 spotId 填上下文中的"景点ID"
          * 查询优惠券/折扣 → 调用 getSpotVouchers，参数 spotId 填上下文中的"景点ID"
          * 查询优惠券库存 → 调用 checkVoucherStock，参数 voucherId 从 getSpotVouchers 返回结果中获取
          * 查询天气 → 调用 checkWeather，参数 location 填景点名称或城市名，lat/lon 填上下文中的坐标（y 为纬度，x 为经度）
        - 绝对不要使用上下文或训练数据中的任何价格/天气信息，必须以工具返回的实时数据为准。
        - 如果工具调用失败或返回空，请如实告知用户当前无相关数据。

        【回答风格】
        - 简洁、有条理，只呈现上下文和工具返回的事实信息。
        - 支持多轮对话，请结合对话历史理解用户的追问。
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

    /** 退购票知识库配置 */
    private TicketRefundConfig ticketRefund = new TicketRefundConfig();

    /** Adaptive 分流配置 */
    private RouterConfig router = new RouterConfig();

    /** CRAG 检索评估配置 */
    private EvaluatorConfig evaluator = new EvaluatorConfig();

    /** Query 改写配置 */
    private RewriteConfig rewrite = new RewriteConfig();

    /** ReACT Agent 配置 */
    private AgentConfig agent = new AgentConfig();

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

    @Data
    public static class RouterConfig {
        /** 是否启用 Adaptive 分流 */
        private boolean enabled = true;
    }

    @Data
    public static class EvaluatorConfig {
        /** 高可信阈值（0~1），>= 此值视为充分相关 */
        private double confidentThreshold = 0.6;
        /** 模糊阈值（0~1），>= 此值视为部分相关，低于则回退 */
        private double ambiguousThreshold = 0.4;
    }

    @Data
    public static class RewriteConfig {
        /** Query 最短字符数，低于此值跳过改写直接透传（默认 5） */
        private int minQueryLength = 5;
    }

    @Data
    public static class AgentConfig {
        /** 是否启用 Agent 模式端点（默认启用，用于灰度发布） */
        private boolean enabled = true;
        /** 最大工具调用轮次 */
        private int maxIterations = 10;
        /** Agent 单次请求超时（毫秒） */
        private long timeoutMs = 30_000;
        /** Planner 配置 */
        private PlannerConfig planner = new PlannerConfig();
        /** Agent 系统提示词（ReACT 指令） */
        private String systemPrompt = """
                你是一个智能旅游助手，可以搜索景点知识库和调用实时数据工具。请逐步推理用户问题。

                【可用工具】
                - searchKnowledgeBase(query): 在景点知识库中搜索。query 为检索关键词串。
                - getSpotPrice(spotId): 查询景点门票实时价格。spotId 为整数。
                - getSpotVouchers(spotId): 查询景点可用优惠券/折扣。
                - checkVoucherStock(voucherId): 检查优惠券实时库存。
                - checkWeather(location, lat, lon): 查询地点实时天气。location 为地名，lat/lon 可选。

                【推理流程】
                1. 分析用户需要什么信息（景点信息？价格？天气？）
                2. 如需景点信息 → 调用 searchKnowledgeBase，用提取的关键词检索
                3. 观察检索结果的"[置信度]"标记：
                   - CONFIDENT → 结果可靠，可直接使用
                   - AMBIGUOUS → 结果可能不完全匹配，谨慎使用
                   - INSUFFICIENT → 结果不足，尝试换关键词重新检索，或告知用户未找到
                4. 如需实时数据（价格/优惠/天气）→ 根据检索结果中的 spotId 调用对应工具
                5. 信息充足后 → 生成简洁、有条理的回答
                6. 同一工具同一查询最多调用 2 次，避免死循环
                7. 简单的问候/感谢直接回复，不需要调用工具

                【回答规范】
                - 引用检索结果中的景点信息时注明景点名称
                - 价格/天气以工具返回的实时数据为准
                - 未找到信息时如实告知用户
                """;
    }

    @Data
    public static class PlannerConfig {
        /** 是否启用 Planner 分解 */
        private boolean enabled = true;
        /** 每个子任务最大 ReACT 迭代次数 */
        private int maxSubIterations = 3;
        /** 子任务执行超时（毫秒） */
        private long subTimeoutMs = 15_000;
        /** 最大子任务数（防止过度分解） */
        private int maxSubTasks = 5;
    }

    @Data
    public static class TicketRefundConfig {
        /** Qdrant 配置 */
        private QdrantSubConfig qdrant = new QdrantSubConfig();
        /** ES 配置 */
        private EsSubConfig es = new EsSubConfig();

        @Data
        public static class QdrantSubConfig {
            private String collectionName = "ticket_refund_kb";
        }

        @Data
        public static class EsSubConfig {
            private String indexName = "ticket_refund_chunks";
            private int topK = 10;
        }
    }
}
