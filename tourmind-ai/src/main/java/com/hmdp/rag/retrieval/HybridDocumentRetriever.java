package com.hmdp.rag.retrieval;

import com.hmdp.config.RagConfig;
import com.hmdp.entity.Spot;
import com.hmdp.entity.SpotType;
import com.hmdp.mapper.SpotMapper;
import com.hmdp.mapper.SpotTypeMapper;
import com.hmdp.rag.RetrievalContext;
import com.hmdp.rag.client.QwenRerankClient;
import com.hmdp.rag.evaluation.RetrievalEvaluator;
import com.hmdp.rag.router.TicketRefundConstants;
import com.hmdp.rag.index.ParentChildIndexer;
import com.hmdp.rag.retrieval.MmrDiversifier;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 混合文档检索器 — 主知识库 + 退购票知识库，ES BM25 + Qdrant 向量 + RRF 融合 + 可选重排。
 *
 * <h3>检索管线</h3>
 * <ol>
 *   <li><b>意图路由</b> — 关键词匹配判断是否需搜退购票知识库</li>
 *   <li><b>主知识库</b>：Qdrant 向量（仅子文档）→ ES BM25 → RRF 融合 → 可选重排
 *       → DB批量查询 → 距离排序 → 父文档加载</li>
 *   <li><b>退购票知识库</b>（按需）：Qdrant 向量 → ES BM25 → RRF 融合 → 附加到结果</li>
 *   <li>合并去重（spotId + chunkTopic）→ 返回 Documents</li>
 * </ol>
 */
@Slf4j
public class HybridDocumentRetriever implements DocumentRetriever {

    // 退购票意图路由 → 引用 TicketRefundConstants（唯一真相源）

    private final VectorStore vectorStore;
    private final SpotMapper spotMapper;
    private final SpotTypeMapper spotTypeMapper;
    private final RagConfig ragConfig;
    private final EsBm25Retriever esBm25Retriever;
    private final RrfRankFuser rrfRankFuser;
    private final ParentChildIndexer parentChildIndexer;
    private final QwenRerankClient rerankClient;

    /** 退购票知识库组件（可选） */
    private final VectorStore ticketRefundVectorStore;
    private final EsBm25Retriever ticketRefundEsBm25Retriever;

    /** CRAG 检索质量评估器 */
    private final RetrievalEvaluator retrievalEvaluator;

    /** MMR 多样性重排器（可选，未启用时为 null） */
    private final MmrDiversifier mmrDiversifier;

    /** typeId → typeName 本地缓存 */
    private final ConcurrentHashMap<Long, String> typeNameCache = new ConcurrentHashMap<>();

    public HybridDocumentRetriever(VectorStore vectorStore,
                                    SpotMapper spotMapper,
                                    SpotTypeMapper spotTypeMapper,
                                    RagConfig ragConfig,
                                    EsBm25Retriever esBm25Retriever,
                                    RrfRankFuser rrfRankFuser,
                                    ParentChildIndexer parentChildIndexer,
                                    QwenRerankClient rerankClient,
                                    VectorStore ticketRefundVectorStore,
                                    EsBm25Retriever ticketRefundEsBm25Retriever,
                                    RetrievalEvaluator retrievalEvaluator,
                                    MmrDiversifier mmrDiversifier) {
        this.vectorStore = vectorStore;
        this.spotMapper = spotMapper;
        this.spotTypeMapper = spotTypeMapper;
        this.ragConfig = ragConfig;
        this.esBm25Retriever = esBm25Retriever;
        this.rrfRankFuser = rrfRankFuser;
        this.parentChildIndexer = parentChildIndexer;
        this.rerankClient = rerankClient;
        this.ticketRefundVectorStore = ticketRefundVectorStore;
        this.ticketRefundEsBm25Retriever = ticketRefundEsBm25Retriever;
        this.retrievalEvaluator = retrievalEvaluator;
        this.mmrDiversifier = mmrDiversifier;
    }

    @PostConstruct
    public void loadTypeNames() {
        List<SpotType> types = spotTypeMapper.selectList(null);
        for (SpotType type : types) {
            typeNameCache.put(type.getId(), type.getName());
        }
        log.info("HybridDocumentRetriever 加载了 {} 个景点类型", types.size());
    }

    // ==================== DocumentRetriever 接口 ====================

    @Override
    public List<Document> retrieve(Query query) {
        String queryText = query.text();
        log.debug("HybridDocumentRetriever.retrieve() 开始: query={}", queryText);

        // ① 意图路由：判断是否需要搜退购票知识库
        boolean needTicketRefund = isTicketRefundQuery(queryText);
        log.debug("意图路由: needTicketRefund={}", needTicketRefund);

        // ==================== 主知识库检索 ====================
        List<Document> mainDocs = retrieveMainKB(queryText);
        log.debug("主知识库检索返回 {} 个文档", mainDocs.size());

        // ==================== 退购票知识库检索（按需） ====================
        List<Document> ticketRefundDocs = Collections.emptyList();
        if (needTicketRefund && ticketRefundVectorStore != null && ticketRefundEsBm25Retriever != null) {
            ticketRefundDocs = retrieveTicketRefundKB(queryText);
            log.debug("退购票知识库检索返回 {} 个文档", ticketRefundDocs.size());
        }

        // ==================== 合并去重 ====================
        List<Document> merged = mergeDocuments(mainDocs, ticketRefundDocs);

        // ==================== CRAG 检索评估 ====================
        RetrievalEvaluator.EvaluationResult evalResult = retrievalEvaluator.evaluate(merged);
        RetrievalContext ctx = RetrievalContext.current();
        ctx.setRetrievalConfidence(evalResult.getConfidence().name());
        ctx.setMaxRetrievalScore(evalResult.getMaxScore());
        log.debug("CRAG 评估: confidence={}, maxScore={}", evalResult.getConfidence(), evalResult.getMaxScore());

        log.debug("HybridDocumentRetriever.retrieve() 完成，合并输出 {} 个文档", merged.size());
        return merged;
    }

    // ==================== 主知识库检索管线 ====================

    private List<Document> retrieveMainKB(String queryText) {
        RagConfig.EsConfig esConfig = ragConfig.getEs();
        int maxContextSpots = ragConfig.getMaxContextSpots();

        // ① Qdrant 向量检索（仅子文档）
        List<Document> vectorDocs = vectorSearch(queryText, esConfig.getTopK());
        log.debug("主KB Qdrant 检索命中 {} 条", vectorDocs.size());

        // ② ES BM25 关键词检索
        List<RrfRankFuser.ScoredDoc> esResults = esBm25Retriever.search(queryText, esConfig.getTopK());
        log.debug("主KB ES BM25 检索命中 {} 条", esResults.size());

        // 如果两路都为空，返回空
        if (vectorDocs.isEmpty() && esResults.isEmpty()) {
            clearContext();
            return Collections.emptyList();
        }

        return buildMainResults(queryText, vectorDocs, esResults, maxContextSpots);
    }

    private List<Document> buildMainResults(String queryText, List<Document> vectorDocs,
                                             List<RrfRankFuser.ScoredDoc> esResults,
                                             int maxContextSpots) {
        RagConfig.RrfConfig rrfConfig = ragConfig.getRrf();

        // 【P1 自适应检索】根据复杂度调整 topK
        int adaptiveTopK = computeAdaptiveTopK(rrfConfig.getTopK());

        // ③ 转换为统一 ScoredDoc 格式
        List<RrfRankFuser.ScoredDoc> vecScored = fromVectorResults(vectorDocs);

        // ④ RRF 排名融合
        List<RrfRankFuser.FusedResult> fused = rrfRankFuser.fuse(
                esResults, vecScored, adaptiveTopK);
        log.debug("主KB RRF 融合: {} 个候选 (adaptive topK={})", fused.size(), adaptiveTopK);

        // ⑤ 重排（可选，自适应启用）
        if (rerankClient != null && shouldEnableRerank()) {
            fused = applyRerank(queryText, fused);
        }

        // ⑥ 【P1 MMR 多样性】在提取 spotId 前应用 MMR
        if (mmrDiversifier != null && ragConfig.getMmr().isEnabled()) {
            int mmrTopK = Math.min(fused.size(), maxContextSpots);
            fused = mmrDiversifier.diversify(fused, mmrTopK);
            log.debug("MMR 多样性重排: {} 个候选", fused.size());
        }

        // ⑦ 提取 spotId 列表
        List<Long> spotIds = fused.stream()
                .map(fr -> {
                    try { return Long.valueOf(fr.getSpotId()); }
                    catch (NumberFormatException e) { return null; }
                })
                .filter(Objects::nonNull)
                .distinct()
                .limit(maxContextSpots)
                .collect(Collectors.toList());

        if (spotIds.isEmpty()) {
            clearContext();
            return Collections.emptyList();
        }

        // ⑧ DB 批量查询（保持融合排序）
        List<Spot> spots = spotMapper.selectBatchIds(spotIds);
        Map<Long, Spot> spotMap = spots.stream()
                .collect(Collectors.toMap(Spot::getId, Function.identity(), (a, b) -> a));
        List<Spot> orderedSpots = spotIds.stream()
                .map(spotMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // ⑨ 距离排序
        RetrievalContext ctx = RetrievalContext.current();
        if (ctx.getUserX() != null && ctx.getUserY() != null) {
            orderedSpots = sortByDistance(orderedSpots, ctx.getUserX(), ctx.getUserY());
        }

        // ⑩ ThreadLocal 缓存
        ctx.setRetrievedSpots(orderedSpots);

        // ⑪ 加载父文档全文 → 构造返回 Document
        return orderedSpots.stream()
                .map(this::spotToParentDocument)
                .collect(Collectors.toList());
    }

    // ==================== 退购票知识库检索管线 ====================

    /**
     * 退购票知识库检索 — 与主 KB 相同的管线但更轻量（无父文档加载）。
     */
    private List<Document> retrieveTicketRefundKB(String queryText) {
        RagConfig.TicketRefundConfig trConfig = ragConfig.getTicketRefund();
        int topK = trConfig.getEs().getTopK();

        // 向量检索
        List<Document> vectorDocs = ticketRefundVectorSearch(queryText, topK);
        log.debug("退购票KB Qdrant 检索命中 {} 条", vectorDocs.size());

        // BM25 检索
        List<RrfRankFuser.ScoredDoc> esResults = ticketRefundEsBm25Retriever.search(queryText, topK);
        log.debug("退购票KB ES BM25 检索命中 {} 条", esResults.size());

        if (vectorDocs.isEmpty() && esResults.isEmpty()) {
            return Collections.emptyList();
        }

        // 转换 + RRF 融合
        List<RrfRankFuser.ScoredDoc> vecScored = fromVectorResults(vectorDocs);
        List<RrfRankFuser.FusedResult> fused = rrfRankFuser.fuse(
                esResults, vecScored, ragConfig.getRrf().getTopK());

        // 转换为 Document（直接用 chunk 文本，无需父文档加载）
        return fused.stream()
                .map(fr -> new Document(fr.getText(), fr.getMetadata()))
                .collect(Collectors.toList());
    }

    /**
     * 退购票知识库向量检索。
     */
    private List<Document> ticketRefundVectorSearch(String queryText, int topK) {
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(queryText)
                    .topK(topK)
                    .similarityThreshold(ragConfig.getSimilarityThreshold())
                    .build();
            return ticketRefundVectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.error("退购票KB Qdrant 检索异常: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ==================== 意图路由 ====================

    /**
     * 基于关键词判断用户查询是否涉及购票/退票相关。
     */
    private boolean isTicketRefundQuery(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return false;
        }
        for (String keyword : TicketRefundConstants.KEYWORDS) {
            if (queryText.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 合并去重 ====================

    /**
     * 合并主 KB 和退购票 KB 的结果，按 (spotId + chunkTopic) 防御性去重。
     */
    private List<Document> mergeDocuments(List<Document> mainDocs, List<Document> ticketRefundDocs) {
        if (ticketRefundDocs.isEmpty()) {
            return mainDocs;
        }

        // 收集已有文档的 (spotId, chunkTopic) 指纹
        Set<String> seen = new HashSet<>();
        List<Document> merged = new ArrayList<>(mainDocs);
        for (Document doc : mainDocs) {
            seen.add(fingerprint(doc));
        }

        // 追加未重复的退购票文档
        for (Document doc : ticketRefundDocs) {
            if (seen.add(fingerprint(doc))) {
                merged.add(doc);
            }
        }

        return merged;
    }

    private String fingerprint(Document doc) {
        String spotId = (String) doc.getMetadata().get("spotId");
        String chunkTopic = (String) doc.getMetadata().get("chunkTopic");
        String category = (String) doc.getMetadata().get("category");
        return (spotId != null ? spotId : "") + "|"
                + (chunkTopic != null ? chunkTopic : "")
                + "|" + (category != null ? category : "");
    }

    // ==================== 供 Service 层调用的 API ====================

    public void setUserCoordinates(Double userX, Double userY) {
        RetrievalContext ctx = RetrievalContext.current();
        ctx.setUserX(userX);
        ctx.setUserY(userY);
    }

    public List<Spot> getLastRetrievedSpots() {
        return RetrievalContext.current().getRetrievedSpots();
    }

    public String getLastRetrievalConfidence() {
        return RetrievalContext.current().getRetrievalConfidence();
    }

    public double getLastMaxRetrievalScore() {
        return RetrievalContext.current().getMaxRetrievalScore();
    }

    /** 获取 spotId → 距离 映射，供 Service 层构建 DTO 使用 */
    public Map<Long, Double> getLastSpotDistances() {
        return RetrievalContext.current().getSpotDistances();
    }

    /** 获取最近一次查询的复杂度（供 Service 层构建响应） */
    public int getLastQueryComplexity() {
        return RetrievalContext.current().getQueryComplexity();
    }

    public void clearContext() {
        RetrievalContext.clear();
    }

    // ==================== 内部检索方法 ====================

    /**
     * Qdrant 向量检索 — 仅搜索子文档（docType="child"），按 spotId 去重。
     */
    private List<Document> vectorSearch(String queryText, int topK) {
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(queryText)
                    .topK(topK)
                    .similarityThreshold(computeAdaptiveThreshold())
                    .filterExpression(new FilterExpressionBuilder()
                            .eq(ParentChildIndexer.META_DOC_TYPE, "child")
                            .build())
                    .build();

            List<Document> docs = vectorStore.similaritySearch(request);

            // 按 spotId 去重，保留最高分的（靠前的）
            Set<String> seen = new LinkedHashSet<>();
            List<Document> deduped = new ArrayList<>();
            for (Document doc : docs) {
                Object spotIdObj = doc.getMetadata().get("spotId");
                String spotId = spotIdObj != null ? spotIdObj.toString() : null;
                if (spotId != null && seen.add(spotId)) {
                    deduped.add(doc);
                }
            }
            return deduped;

        } catch (Exception e) {
            log.error("Qdrant 向量检索异常: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 将 Qdrant Document 列表转为统一的 ScoredDoc 格式（rank-based 评分）。
     */
    private List<RrfRankFuser.ScoredDoc> fromVectorResults(List<Document> vectorDocs) {
        if (vectorDocs.isEmpty()) return Collections.emptyList();
        int total = vectorDocs.size();
        List<RrfRankFuser.ScoredDoc> docs = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            Document doc = vectorDocs.get(i);
            Object spotIdObj = doc.getMetadata().get("spotId");
            String spotId = spotIdObj != null ? spotIdObj.toString() : "";
            // 排名越靠前分数越高（RRF 只用排名，分数仅用于参考）
            double score = 1.0 - (double) i / total;
            docs.add(new RrfRankFuser.ScoredDoc(
                    doc.getId(), spotId, score, doc.getText(), doc.getMetadata()));
        }
        return docs;
    }

    /**
     * qwen3-rerank 两阶段精排。
     */
    private List<RrfRankFuser.FusedResult> applyRerank(
            String queryText, List<RrfRankFuser.FusedResult> fused) {

        RagConfig.RerankerConfig rerankerConfig = ragConfig.getReranker();
        int rerankCount = Math.min(fused.size(), rerankerConfig.getTopK());

        List<String> docTexts = fused.stream()
                .limit(rerankCount)
                .map(fr -> fr.getText() != null ? fr.getText() : "")
                .collect(Collectors.toList());

        try {
            List<QwenRerankClient.RerankResult> rerankResults =
                    rerankClient.rerank(queryText, docTexts);
            if (rerankResults == null || rerankResults.isEmpty()) {
                log.debug("重排无结果，使用 RRF 融合结果");
                return fused;
            }

            // 用重排结果重建 fused 列表
            Map<String, RrfRankFuser.FusedResult> fusedMap = fused.stream()
                    .collect(Collectors.toMap(RrfRankFuser.FusedResult::getSpotId,
                            Function.identity(), (a, b) -> a, LinkedHashMap::new));

            List<RrfRankFuser.FusedResult> reranked = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();

            for (QwenRerankClient.RerankResult rr : rerankResults) {
                for (RrfRankFuser.FusedResult fr : fused) {
                    if (rr.getText() != null && rr.getText().equals(fr.getText())
                            && seen.add(fr.getSpotId())) {
                        reranked.add(new RrfRankFuser.FusedResult(
                                fr.getSpotId(), rr.getScore(), fr.getText(), fr.getMetadata()));
                        break;
                    }
                }
            }

            log.debug("重排完成: {} → {} 个结果", fused.size(), reranked.size());
            return reranked.isEmpty() ? fused : reranked;

        } catch (Exception e) {
            log.warn("重排异常，使用 RRF 融合结果: {}", e.getMessage());
            return fused;
        }
    }

    // ==================== 父文档加载 ====================

    private Document spotToParentDocument(Spot spot) {
        try {
            String parentText = parentChildIndexer.loadParentText(spot.getId());
            if (parentText != null && !parentText.isEmpty()) {
                return buildDocument(parentText, spot);
            }
        } catch (Exception e) {
            log.warn("加载父文档失败: spotId={}, 回退 spotToDocument: {}", spot.getId(), e.getMessage());
        }
        return spotToDocument(spot);
    }

    private Document buildDocument(String text, Spot spot) {
        // 在父文档文本前注入景点ID，确保 LLM 能获取 spotId 用于 Function Calling
        String enrichedText = "景点ID：" + spot.getId() + "\n" + text;
        return new Document(enrichedText, buildMetadata(spot));
    }

    private Document spotToDocument(Spot spot) {
        String typeName = getTypeName(spot.getTypeId());
        return new Document(buildSpotContent(spot, typeName), buildMetadata(spot));
    }

    private Map<String, Object> buildMetadata(Spot spot) {
        String typeName = getTypeName(spot.getTypeId());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("spotId", spot.getId().toString());
        metadata.put("spotName", spot.getName());
        metadata.put("typeName", typeName);
        if (spot.getArea() != null) metadata.put("area", spot.getArea());
        if (spot.getAddress() != null) metadata.put("address", spot.getAddress());
        metadata.put("ticketPrice", spot.getTicketPrice());
        metadata.put("score", spot.getScore());
        if (spot.getOpenHours() != null) metadata.put("openHours", spot.getOpenHours());
        if (spot.getX() != null) metadata.put("x", spot.getX());
        if (spot.getY() != null) metadata.put("y", spot.getY());
        Double distance = getSpotDistance(spot.getId());
        if (distance != null) metadata.put("distance", distance);
        return metadata;
    }

    private String buildSpotContent(Spot spot, String typeName) {
        StringBuilder sb = new StringBuilder();
        sb.append("景点ID：").append(spot.getId()).append("\n");
        sb.append("景点名称：").append(spot.getName()).append("\n");
        sb.append("景点类型：").append(typeName).append("\n");
        if (spot.getArea() != null) sb.append("所在区域：").append(spot.getArea()).append("\n");
        if (spot.getAddress() != null) sb.append("具体地址：").append(spot.getAddress()).append("\n");
        sb.append("评分：").append(spot.getScore()).append("分\n");
        if (spot.getOpenHours() != null) sb.append("开放时间：").append(spot.getOpenHours()).append("\n");
        Double distance = getSpotDistance(spot.getId());
        if (distance != null)
            sb.append(String.format("距离：%.2f 公里\n", distance));
        return sb.toString();
    }

    // ==================== 内部工具方法 ====================

    private String getTypeName(Long typeId) {
        if (typeId == null) return "未知";
        return typeNameCache.getOrDefault(typeId, "未知");
    }

    private List<Spot> sortByDistance(List<Spot> spots, Double userX, Double userY) {
        // 计算距离到独立 map，不污染 Spot 实体（避免 MyBatis 缓存副作用）
        Map<Long, Double> distanceMap = new HashMap<>();
        for (Spot spot : spots) {
            if (spot.getX() != null && spot.getY() != null) {
                double dx = userX - spot.getX();
                double dy = userY - spot.getY();
                distanceMap.put(spot.getId(), Math.sqrt(dx * dx + dy * dy));
            }
        }
        // 存入 ThreadLocal 上下文，供 Service 层构建 DTO 和 Document metadata 使用
        RetrievalContext ctx = RetrievalContext.current();
        ctx.setSpotDistances(distanceMap);

        return spots.stream()
                .sorted(Comparator.comparing(
                        (Spot s) -> distanceMap.get(s.getId()),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    /** 从上下文获取 spotId 对应的距离，无坐标时返回 null */
    private Double getSpotDistance(Long spotId) {
        return RetrievalContext.current().getSpotDistances().get(spotId);
    }

    // ==================== P1 自适应参数 ====================

    /** 根据 queryComplexity 计算自适应 topK */
    private int computeAdaptiveTopK(int defaultTopK) {
        RagConfig.AdaptiveRetrievalConfig arc = ragConfig.getAdaptiveRetrieval();
        if (!arc.isEnabled()) return defaultTopK;
        int complexity = RetrievalContext.current().getQueryComplexity();
        if (complexity <= 2) return arc.getSimpleTopK();
        if (complexity == 3) return arc.getMediumTopK();
        return arc.getComplexTopK();
    }

    /** 根据 queryComplexity 计算自适应相似度阈值 */
    private double computeAdaptiveThreshold() {
        RagConfig.AdaptiveRetrievalConfig arc = ragConfig.getAdaptiveRetrieval();
        if (!arc.isEnabled()) return ragConfig.getSimilarityThreshold();
        int complexity = RetrievalContext.current().getQueryComplexity();
        return complexity <= 2 ? arc.getSimpleThreshold() : arc.getComplexThreshold();
    }

    /** 根据 queryComplexity 决定是否启用重排 */
    private boolean shouldEnableRerank() {
        if (!ragConfig.getReranker().isEnabled()) return false;
        if (!ragConfig.getAdaptiveRetrieval().isEnabled()) return true;
        return RetrievalContext.current().getQueryComplexity() >= 3;
    }
}
