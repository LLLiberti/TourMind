package com.hmdp.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.hmdp.mapper.SpotKnowledgeMapper;
import com.hmdp.mapper.SpotMapper;
import com.hmdp.mapper.SpotTypeMapper;
import com.hmdp.rag.cache.RetrievalCacheManager;
import com.hmdp.rag.client.QwenRerankClient;
import com.hmdp.rag.evaluation.CragCorrector;
import com.hmdp.rag.evaluation.RetrievalEvaluator;
import com.hmdp.rag.generation.GenerationGuard;
import com.hmdp.rag.index.EsChunkIndexer;
import com.hmdp.rag.index.ParentChildIndexer;
import com.hmdp.rag.index.TicketRefundIndexer;
import com.hmdp.rag.query.CompressionQueryTransformer;
import com.hmdp.rag.query.RewriteQueryTransformer;
import com.hmdp.rag.retrieval.EsBm25Retriever;
import com.hmdp.rag.retrieval.HybridDocumentRetriever;
import com.hmdp.rag.retrieval.MmrDiversifier;
import com.hmdp.rag.retrieval.RrfRankFuser;
import com.hmdp.rag.router.QueryRouter;
import com.hmdp.service.impl.ConversationSummaryService;
import com.hmdp.tool.SpotTools;
import com.hmdp.tool.WeatherTools;
import io.qdrant.client.QdrantClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Spring AI 核心配置 — Agentic RAG 架构（ReACT Agent + 检索/工具组件）。
 *
 * <h3>架构</h3>
 * <p>RAG 检索已迁移至 Agent 路径（{@code ReActAgentLoop} + {@code SearchKnowledgeBaseTool}），
 * 由 LLM 在 ReACT 循环中自主决定是否检索、检索几次、何时调用实时数据工具。</p>
 *
 * <h3>ChatClient</h3>
 * <p>仅保留 {@code MessageChatMemoryAdvisor} + {@code SimpleLoggerAdvisor} + {@code SpotTools}/{@code WeatherTools}，
 * 用于闲聊/指定景点问答等简单场景，不再承担检索职责。</p>
 *
 * <h3>VectorStore</h3>
 * <p>Qdrant VectorStore 由 Spring AI 自动配置，配置见 application-ai.yaml。</p>
 */
@Configuration
public class AIConfig {

    private static final Logger log = LoggerFactory.getLogger(AIConfig.class);

    // ==================== 模块化 RAG 组件 ====================

    /**
     * 混合文档检索器 — ES BM25 + Qdrant 向量 + RRF 融合 + 可选重排。
     */
    @Bean
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    public HybridDocumentRetriever spotDocumentRetriever(VectorStore vectorStore,
                                                          SpotMapper spotMapper,
                                                          SpotTypeMapper spotTypeMapper,
                                                          RagConfig ragConfig,
                                                          EsBm25Retriever esBm25Retriever,
                                                          RrfRankFuser rrfRankFuser,
                                                          ParentChildIndexer parentChildIndexer,
                                                          @org.springframework.beans.factory.annotation.Autowired(
                                                                  required = false)
                                                          QwenRerankClient rerankClient,
                                                          @Qualifier("ticketRefundVectorStore")
                                                          VectorStore ticketRefundVectorStore,
                                                          @Qualifier("ticketRefundEsBm25Retriever")
                                                          EsBm25Retriever ticketRefundEsBm25Retriever,
                                                          RetrievalEvaluator retrievalEvaluator,
                                                          MmrDiversifier mmrDiversifier) {
        return new HybridDocumentRetriever(vectorStore, spotMapper, spotTypeMapper, ragConfig,
                esBm25Retriever, rrfRankFuser, parentChildIndexer, rerankClient,
                ticketRefundVectorStore, ticketRefundEsBm25Retriever, retrievalEvaluator,
                mmrDiversifier);
    }

    /**
     * Elasticsearch BM25 检索器 — 使用 ik_max_word 中文分词进行关键词检索。
     */
    @Bean
    public EsBm25Retriever esBm25Retriever(ElasticsearchClient esClient, RagConfig ragConfig) {
        return new EsBm25Retriever(esClient, ragConfig.getEs().getIndexName());
    }

    /**
     * ES 索引管理器 — 负责 chunk 的写入和删除。
     */
    @Bean
    public EsChunkIndexer esChunkIndexer(ElasticsearchClient esClient, RagConfig ragConfig) {
        return new EsChunkIndexer(esClient, ragConfig.getEs().getIndexName());
    }

    // ==================== 退购票知识库组件 ====================

    /**
     * 退购票知识库 Qdrant VectorStore — 独立的 collection，存储购票须知和退票条件子文档。
     */
    @Bean
    public VectorStore ticketRefundVectorStore(QdrantClient qdrantClient,
                                                EmbeddingModel embeddingModel,
                                                RagConfig ragConfig) {
        return QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName(ragConfig.getTicketRefund().getQdrant().getCollectionName())
                .initializeSchema(true)
                .build();
    }

    /**
     * 用户记忆 Qdrant VectorStore — 独立的 collection，存储长期记忆（非结构化知识/事件）。
     */
    @Bean
    public VectorStore userMemoriesVectorStore(QdrantClient qdrantClient,
                                                EmbeddingModel embeddingModel) {
        return QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName("user_memories")
                .initializeSchema(true)
                .build();
    }

    /**
     * 退购票知识库 ES 索引管理器 — 独立的 ES index。
     */
    @Bean
    public EsChunkIndexer ticketRefundEsChunkIndexer(ElasticsearchClient esClient, RagConfig ragConfig) {
        return new EsChunkIndexer(esClient, ragConfig.getTicketRefund().getEs().getIndexName());
    }

    /**
     * 退购票知识库 ES BM25 检索器。
     */
    @Bean
    public EsBm25Retriever ticketRefundEsBm25Retriever(ElasticsearchClient esClient, RagConfig ragConfig) {
        return new EsBm25Retriever(esClient, ragConfig.getTicketRefund().getEs().getIndexName());
    }

    /**
     * 退购票知识库索引器 — 将购票须知和退票条件写入 Qdrant + ES。
     */
    @Bean
    public TicketRefundIndexer ticketRefundIndexer(
            @Qualifier("ticketRefundVectorStore") VectorStore ticketRefundVectorStore,
            @Qualifier("ticketRefundEsChunkIndexer") EsChunkIndexer ticketRefundEsChunkIndexer) {
        return new TicketRefundIndexer(ticketRefundVectorStore, ticketRefundEsChunkIndexer);
    }

    // ==================== Adaptive 分流 + CRAG 评估 ====================

    /**
     * Adaptive 查询分流器 — 闲聊直接 LLM 回答，知识查询走 RAG。
     */
    @Bean
    public QueryRouter queryRouter() {
        return new QueryRouter();
    }

    /**
     * CRAG 检索质量评估器 — 低质量结果自动回退，减少幻觉。
     */
    @Bean
    public RetrievalEvaluator retrievalEvaluator(RagConfig ragConfig) {
        return new RetrievalEvaluator(
                ragConfig.getEvaluator().getConfidentThreshold(),
                ragConfig.getEvaluator().getAmbiguousThreshold());
    }

    /**
     * RRF 排名融合器 — 将 ES BM25 和 Qdrant 向量两路排名结果进行 RRF 融合。
     */
    @Bean
    public RrfRankFuser rrfRankFuser(RagConfig ragConfig) {
        return new RrfRankFuser(ragConfig.getRrf().getRankConstant());
    }

    /**
     * 父子文档索引协调器 — 封装父文档创建、子文档分块、Qdrant/ES/MySQL/Redis 全量写入。
     */
    @Bean
    public ParentChildIndexer parentChildIndexer(VectorStore vectorStore,
                                                  EsChunkIndexer esChunkIndexer,
                                                  SpotKnowledgeMapper spotKnowledgeMapper,
                                                  SpotMapper spotMapper,
                                                  RedisTemplate<Object, Object> redisTemplate) {
        return new ParentChildIndexer(vectorStore, esChunkIndexer, spotKnowledgeMapper,
                spotMapper, redisTemplate);
    }

    /**
     * qwen3-rerank HTTP 客户端 — 仅在 rag.reranker.enabled=true 时创建。
     */
    @Bean
    @ConditionalOnProperty(prefix = "rag.reranker", name = "enabled", havingValue = "true")
    public QwenRerankClient qwenRerankClient(RagConfig ragConfig) {
        return new QwenRerankClient(ragConfig.getReranker());
    }

    // ==================== Query 变换（Agent 路径复用） ====================

    /**
     * 多轮指代消解 — 在 Query 改写前解析代词/序数指代。
     */
    @Bean
    public CompressionQueryTransformer compressionQueryTransformer(
            @Qualifier("deepSeekChatModel") ChatModel chatModel) {
        return new CompressionQueryTransformer(chatModel);
    }

    /**
     * Query 改写为关键词 — 供 Agent 路径的 SearchKnowledgeBaseTool 使用。
     */
    @Bean
    public RewriteQueryTransformer rewriteQueryTransformer(
            @Qualifier("deepSeekChatModel") ChatModel chatModel, RagConfig ragConfig) {
        return new RewriteQueryTransformer(chatModel, ragConfig.getRewrite().getMinQueryLength());
    }

    // ==================== P1 新增 Beans ====================

    /** MMR 多样性重排器 */
    @Bean
    public MmrDiversifier mmrDiversifier(RagConfig ragConfig) {
        return new MmrDiversifier(ragConfig.getMmr().getLambda());
    }

    /** CRAG 纠正器 */
    @Bean
    public CragCorrector cragCorrector(SpotMapper spotMapper,
                                        ParentChildIndexer parentChildIndexer,
                                        RagConfig ragConfig) {
        CragCorrector.WebSearchClient webClient = null;
        if (ragConfig.getCragCorrection().isWebFallbackEnabled()
                && !ragConfig.getCragCorrection().getWebSearchApiKey().isEmpty()) {
            webClient = new CragCorrector.BaiduSearchClient(
                    ragConfig.getCragCorrection().getWebSearchApiKey(),
                    ragConfig.getCragCorrection().getWebSearchEndpoint());
        }
        return new CragCorrector(spotMapper, parentChildIndexer, ragConfig, webClient);
    }

    /** 生成质量守护 */
    @Bean
    public GenerationGuard generationGuard(
            @Qualifier("deepSeekChatModel") ChatModel chatModel) {
        return new GenerationGuard(chatModel);
    }

    /** 检索结果缓存管理器（依赖 RedisTemplate） */
    @Bean
    public RetrievalCacheManager retrievalCacheManager(
            RedisTemplate<String, String> redisTemplate, RagConfig ragConfig) {
        return new RetrievalCacheManager(redisTemplate, ragConfig.getCache().isEnabled());
    }

    /** 对话摘要服务 */
    @Bean
    public ConversationSummaryService conversationSummaryService(
            @Qualifier("deepSeekChatModel") ChatModel chatModel) {
        return new ConversationSummaryService(chatModel);
    }

    // ==================== 聊天记忆 ====================

    /**
     * 聊天记忆顾问 — 自动处理多轮对话记忆的获取和保存
     *
     * <p>每轮对话前自动从 ChatMemory 获取历史消息并拼接到 prompt 中，
     * 对话后自动将本轮问答保存到 ChatMemory。</p>
     *
     * <p>会话 ID 通过 advisor 参数传入：{@code chat_memory_conversation_id}</p>
     */
    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory)
                .build();
    }

    // ==================== ChatClient ====================

    /**
     * ChatClient — 仅聊天记忆 + 工具调用。
     *
     * <p>RAG 检索已迁移至 Agent 路径（ReActAgentLoop），
     * ChatClient 不再承担检索职责，仅用于闲聊/指定景点问答。</p>
     */
    @Bean
    public ChatClient chatClient(@Qualifier("deepSeekChatModel") ChatModel chatModel,
                                  MessageChatMemoryAdvisor memoryAdvisor,
                                  SpotTools spotTools,
                                  WeatherTools weatherTools) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(memoryAdvisor, new SimpleLoggerAdvisor())
                .defaultTools(spotTools, weatherTools)
                .build();
    }
}
