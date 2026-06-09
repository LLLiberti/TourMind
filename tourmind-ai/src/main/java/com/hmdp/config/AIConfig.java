package com.hmdp.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.hmdp.mapper.SpotKnowledgeMapper;
import com.hmdp.mapper.SpotMapper;
import com.hmdp.mapper.SpotTypeMapper;
import com.hmdp.rag.client.QwenRerankClient;
import com.hmdp.rag.index.EsChunkIndexer;
import com.hmdp.rag.index.ParentChildIndexer;
import com.hmdp.rag.query.RewriteQueryTransformer;
import com.hmdp.rag.retrieval.EsBm25Retriever;
import com.hmdp.rag.retrieval.HybridDocumentRetriever;
import com.hmdp.rag.retrieval.RrfRankFuser;
import com.hmdp.tool.SpotTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Spring AI 核心配置 — 模块化 RAG + Function Calling 混合架构。
 *
 * <h3>Advisor 链</h3>
 * <ol>
 *   <li><b>MessageChatMemoryAdvisor</b> — 自动管理多轮对话记忆</li>
 *   <li><b>RetrievalAugmentationAdvisor</b> — 模块化 RAG：
 *     CompressionQueryTransformer → RewriteQueryTransformer →
 *     HybridDocumentRetriever（向量+BM25动态混合+重排）→ ContextualQueryAugmenter</li>
 *   <li><b>SimpleLoggerAdvisor</b> — 请求/响应日志</li>
 * </ol>
 *
 * <h3>Function Calling</h3>
 * <p>通过 {@code SpotTools} 注册 3 个工具函数（价格、优惠券、库存），
 * LLM 根据用户问题自动决定是否调用。实时数据不进入 RAG 知识库。</p>
 *
 * <h3>VectorStore</h3>
 * <p>Qdrant VectorStore 由 Spring AI 自动配置，配置见 application-ai.yaml。
 * 本类不手动创建 VectorStore Bean，直接注入自动配置生成的实例。</p>
 *
 * <h3>新增组件</h3>
 * <ul>
 *   <li><b>HybridDocumentRetriever</b> — 混合检索器：ES BM25 + Qdrant 向量 + RRF 融合 + 可选重排</li>
 *   <li><b>EsBm25Retriever</b> — Elasticsearch BM25 关键词检索（ik_max_word 分词）</li>
 *   <li><b>EsChunkIndexer</b> — ES 索引管理器（chunk 写入 / 删除）</li>
 *   <li><b>RrfRankFuser</b> — RRF 排名融合引擎</li>
 *   <li><b>ParentChildIndexer</b> — Father-Child 索引：父文档全文 + 子文档分块（Qdrant + ES + MySQL + Redis）</li>
 *   <li><b>QwenRerankClient</b> — qwen3-rerank 两阶段精排（可选，需外部服务）</li>
 * </ul>
 *
 * @see HybridDocumentRetriever
 * @see EsBm25Retriever
 * @see RrfRankFuser
 * @see SpotTools
 */
@Configuration
public class AIConfig {

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
                                                          QwenRerankClient rerankClient) {
        return new HybridDocumentRetriever(vectorStore, spotMapper, spotTypeMapper, ragConfig,
                esBm25Retriever, rrfRankFuser, parentChildIndexer, rerankClient);
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

    /**
     * 上下文查询增强器 — 将检索到的文档注入到 prompt 中
     */
    @Bean
    public ContextualQueryAugmenter contextualQueryAugmenter(RagConfig ragConfig) {
        return ContextualQueryAugmenter.builder()
                .allowEmptyContext(ragConfig.isAllowEmptyContext())
                .build();
    }

    /**
     * 查询重写变换器 — 将口语化 query 改写为关键词串，提升向量检索召回率。
     *
     * <p>在 {@link CompressionQueryTransformer} 之前执行，
     * 将"西湖有啥好玩的推荐一下呗"改写为"西湖 自然风景区 游览 推荐 好玩 必去 景点"。</p>
     */
    @Bean
    public QueryTransformer rewriteQueryTransformer(
            @Qualifier("deepSeekChatModel") ChatModel chatModel) {
        return new RewriteQueryTransformer(chatModel);
    }

    /**
     * 查询压缩变换器（含历史增强包装） — 在多轮对话中将指代词消解为独立查询
     *
     * <p>{@link CompressionQueryTransformer} 的正确用法要求 {@link Query#history()} 中包含对话历史，
     * 但 Spring AI 1.0.7 的 {@link RetrievalAugmentationAdvisor} 构建 Query 时
     * 取的是 {@code Prompt.getInstructions()}（系统消息），而非对话历史。
     * 此包装器从 {@link Query#context()} 中取 {@code chat_memory_conversation_id}，
     * 通过 {@link ChatMemory} 加载真实对话历史，注入到 Query 后再委托给原压缩器。</p>
     */
    @Bean
    public QueryTransformer compressionQueryTransformer(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            ChatMemory chatMemory) {
        ChatClient.Builder compressionBuilder = ChatClient.builder(chatModel);
        CompressionQueryTransformer delegate = CompressionQueryTransformer.builder()
                .chatClientBuilder(compressionBuilder)
                .build();
        return new HistoryEnrichedQueryTransformer(delegate, chatMemory);
    }

    /**
     * 检索增强顾问 — 模块化 RAG 的核心 Advisor
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>HistoryEnrichedQueryTransformer → CompressionQueryTransformer — 消解指代（如"第二个景点"）</li>
     *   <li>RewriteQueryTransformer — 口语 query 改写为关键词串（提升召回率）</li>
     *   <li>SpotDocumentRetriever — 向量检索相关景点文档</li>
     *   <li>ContextualQueryAugmenter — 将检索到的文档注入 prompt 上下文</li>
     * </ol>
     */
    @Bean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            HybridDocumentRetriever spotDocumentRetriever,
            ContextualQueryAugmenter contextualQueryAugmenter,
            QueryTransformer rewriteQueryTransformer,
            QueryTransformer compressionQueryTransformer) {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(spotDocumentRetriever)
                .queryAugmenter(contextualQueryAugmenter)
                .queryTransformers(compressionQueryTransformer, rewriteQueryTransformer)
                .build();
    }

    // ==================== 内部类 ====================

    /**
     * 为 {@link CompressionQueryTransformer} 注入真实对话历史的包装器。
     *
     * <p>Spring AI 1.0.7 的 {@link RetrievalAugmentationAdvisor} 构建的 {@link Query}
     * 其 {@code history} 来自 {@code Prompt.getInstructions()}（通常为空），
     * 而 {@code context} 中包含 {@code chat_memory_conversation_id}。
     * 此包装器利用 context 中的会话 ID 从 {@link ChatMemory} 加载真实历史，
     * 使压缩 LLM 能正确消解"第二个""第一家"等指代词。</p>
     */
    private static class HistoryEnrichedQueryTransformer implements QueryTransformer {

        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HistoryEnrichedQueryTransformer.class);

        private final CompressionQueryTransformer delegate;
        private final ChatMemory chatMemory;

        HistoryEnrichedQueryTransformer(CompressionQueryTransformer delegate, ChatMemory chatMemory) {
            this.delegate = delegate;
            this.chatMemory = chatMemory;
        }

        @Override
        public Query transform(Query query) {
            log.info(">>> 压缩器收到 query.context() keys: {}", query.context().keySet());
            log.info(">>> 压缩器收到 query.text(): {}", query.text());
            log.info(">>> 压缩器收到 query.history() size: {}",
                    query.history() != null ? query.history().size() : 0);

            String conversationId = (String) query.context().get("chat_memory_conversation_id");
            log.info(">>> conversationId from context: {}", conversationId);

            if (conversationId == null || conversationId.isEmpty()) {
                log.warn(">>> 未找到 conversationId，跳过压缩");
                return delegate.transform(query);
            }

            List<Message> history = chatMemory.get(conversationId);
            log.info(">>> ChatMemory 中的历史消息数: {}", history != null ? history.size() : 0);
            if (history != null && !history.isEmpty()) {
                for (int i = 0; i < Math.min(history.size(), 4); i++) {
                    Message m = history.get(i);
                    log.info(">>>   历史[{}]: {} = {}",
                            i, m.getMessageType(), m.getText().substring(0, Math.min(80, m.getText().length())));
                }
            }

            if (history == null || history.isEmpty()) {
                log.warn(">>> ChatMemory 中无历史消息，跳过压缩");
                return delegate.transform(query);
            }

            Query enriched = query.mutate().history(history).build();
            Query result = delegate.transform(enriched);
            log.info(">>> 压缩结果: {} → {}", query.text(), result.text());
            return result;
        }
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
     * ChatClient — 组装 Advisor 链 + Function Calling 工具
     *
     * <p>Advisor 执行顺序（按 order 值从小到大）：</p>
     * <ol>
     *   <li>MessageChatMemoryAdvisor — 注入对话历史</li>
     *   <li>RetrievalAugmentationAdvisor — 压缩查询 → 检索 → 上下文增强</li>
     *   <li>SimpleLoggerAdvisor — 记录日志</li>
     * </ol>
     *
     * <p>LLM 生成响应时可调用 SpotTools 获取实时数据（价格、优惠券、库存）。</p>
     */
    @Bean
    public ChatClient chatClient(@Qualifier("deepSeekChatModel") ChatModel chatModel,
                                  MessageChatMemoryAdvisor memoryAdvisor,
                                  RetrievalAugmentationAdvisor ragAdvisor,
                                  SpotTools spotTools) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(memoryAdvisor, ragAdvisor, new SimpleLoggerAdvisor())
                .defaultTools(spotTools)
                .build();
    }
}
