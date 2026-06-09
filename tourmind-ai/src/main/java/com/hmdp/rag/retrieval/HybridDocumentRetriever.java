package com.hmdp.rag.retrieval;

import com.hmdp.config.RagConfig;
import com.hmdp.entity.Spot;
import com.hmdp.entity.SpotType;
import com.hmdp.mapper.SpotMapper;
import com.hmdp.mapper.SpotTypeMapper;
import com.hmdp.rag.RetrievalContext;
import com.hmdp.rag.client.QwenRerankClient;
import com.hmdp.rag.index.ParentChildIndexer;
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
 * 混合文档检索器 — ES BM25 + Qdrant 向量 + RRF 融合 + 可选重排。
 *
 * <h3>检索管线</h3>
 * <ol>
 *   <li>Qdrant 向量检索 — 仅在子文档（docType="child"）中搜索</li>
 *   <li>ES BM25 关键词检索 — ik_max_word 中文分词</li>
 *   <li>RRF 排名融合 — 两路排名结果合并排序</li>
 *   <li>[可选] qwen3-rerank 两阶段精排 + 阈值过滤</li>
 *   <li>提取 spotId → DB 批量查询 → 距离排序 → ThreadLocal 缓存</li>
 *   <li>加载父文档全文（Redis → MySQL → 重建）→ 返回 Documents</li>
 * </ol>
 */
@Slf4j
public class HybridDocumentRetriever implements DocumentRetriever {

    private final VectorStore vectorStore;
    private final SpotMapper spotMapper;
    private final SpotTypeMapper spotTypeMapper;
    private final RagConfig ragConfig;
    private final EsBm25Retriever esBm25Retriever;
    private final RrfRankFuser rrfRankFuser;
    private final ParentChildIndexer parentChildIndexer;
    private final QwenRerankClient rerankClient;

    /** typeId → typeName 本地缓存 */
    private final ConcurrentHashMap<Long, String> typeNameCache = new ConcurrentHashMap<>();

    /** Per-request ThreadLocal 上下文 */
    private final ThreadLocal<RetrievalContext> contextHolder = new ThreadLocal<>();

    public HybridDocumentRetriever(VectorStore vectorStore,
                                    SpotMapper spotMapper,
                                    SpotTypeMapper spotTypeMapper,
                                    RagConfig ragConfig,
                                    EsBm25Retriever esBm25Retriever,
                                    RrfRankFuser rrfRankFuser,
                                    ParentChildIndexer parentChildIndexer,
                                    QwenRerankClient rerankClient) {
        this.vectorStore = vectorStore;
        this.spotMapper = spotMapper;
        this.spotTypeMapper = spotTypeMapper;
        this.ragConfig = ragConfig;
        this.esBm25Retriever = esBm25Retriever;
        this.rrfRankFuser = rrfRankFuser;
        this.parentChildIndexer = parentChildIndexer;
        this.rerankClient = rerankClient;
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

        RagConfig.RrfConfig rrfConfig = ragConfig.getRrf();
        RagConfig.EsConfig esConfig = ragConfig.getEs();
        int maxContextSpots = ragConfig.getMaxContextSpots();

        // ① Qdrant 向量检索（仅子文档）
        List<Document> vectorDocs = vectorSearch(queryText, esConfig.getTopK());
        log.debug("Qdrant 向量检索命中 {} 条", vectorDocs.size());

        // ② ES BM25 关键词检索
        List<RrfRankFuser.ScoredDoc> esResults = esBm25Retriever.search(queryText, esConfig.getTopK());
        log.debug("ES BM25 检索命中 {} 条", esResults.size());

        // 如果两路都为空，返回空
        if (vectorDocs.isEmpty() && esResults.isEmpty()) {
            clearContext();
            return Collections.emptyList();
        }

        // ③ 转换为统一 ScoredDoc 格式
        List<RrfRankFuser.ScoredDoc> vecScored = fromVectorResults(vectorDocs);

        // ④ RRF 排名融合
        List<RrfRankFuser.FusedResult> fused = rrfRankFuser.fuse(
                esResults, vecScored, rrfConfig.getTopK());
        log.debug("RRF 融合: {} 个候选", fused.size());

        // ⑤ 重排（可选）
        if (rerankClient != null && ragConfig.getReranker().isEnabled()) {
            fused = applyRerank(queryText, fused);
        }

        // ⑥ 提取 spotId 列表
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

        // ⑦ DB 批量查询（保持融合排序）
        List<Spot> spots = spotMapper.selectBatchIds(spotIds);
        Map<Long, Spot> spotMap = spots.stream()
                .collect(Collectors.toMap(Spot::getId, Function.identity(), (a, b) -> a));
        List<Spot> orderedSpots = spotIds.stream()
                .map(spotMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // ⑧ 距离排序
        RetrievalContext ctx = contextHolder.get();
        if (ctx == null) {
            ctx = new RetrievalContext();
            contextHolder.set(ctx);
        }
        if (ctx.getUserX() != null && ctx.getUserY() != null) {
            orderedSpots = sortByDistance(orderedSpots, ctx.getUserX(), ctx.getUserY());
        }

        // ⑨ ThreadLocal 缓存
        ctx.setRetrievedSpots(orderedSpots);

        // ⑩ 加载父文档全文 → 构造返回 Document
        List<Document> enrichedDocs = orderedSpots.stream()
                .map(this::spotToParentDocument)
                .collect(Collectors.toList());

        log.debug("HybridDocumentRetriever.retrieve() 完成，输出 {} 个文档", enrichedDocs.size());
        return enrichedDocs;
    }

    // ==================== 供 Service 层调用的 API ====================

    public void setUserCoordinates(Double userX, Double userY) {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setUserX(userX);
        ctx.setUserY(userY);
        contextHolder.set(ctx);
    }

    public List<Spot> getLastRetrievedSpots() {
        RetrievalContext ctx = contextHolder.get();
        return ctx != null ? ctx.getRetrievedSpots() : Collections.emptyList();
    }

    public void clearContext() {
        contextHolder.remove();
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
                    .similarityThreshold(ragConfig.getSimilarityThreshold())
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
        return new Document(text, buildMetadata(spot));
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
        if (spot.getDistance() != null) metadata.put("distance", spot.getDistance());
        return metadata;
    }

    private String buildSpotContent(Spot spot, String typeName) {
        StringBuilder sb = new StringBuilder();
        sb.append("景点名称：").append(spot.getName()).append("\n");
        sb.append("景点类型：").append(typeName).append("\n");
        if (spot.getArea() != null) sb.append("所在区域：").append(spot.getArea()).append("\n");
        if (spot.getAddress() != null) sb.append("具体地址：").append(spot.getAddress()).append("\n");
        sb.append("评分：").append(spot.getScore()).append("分\n");
        if (spot.getOpenHours() != null) sb.append("开放时间：").append(spot.getOpenHours()).append("\n");
        if (spot.getDistance() != null)
            sb.append(String.format("距离：%.2f 公里\n", spot.getDistance()));
        return sb.toString();
    }

    // ==================== 内部工具方法 ====================

    private String getTypeName(Long typeId) {
        if (typeId == null) return "未知";
        return typeNameCache.getOrDefault(typeId, "未知");
    }

    private List<Spot> sortByDistance(List<Spot> spots, Double userX, Double userY) {
        return spots.stream()
                .peek(spot -> {
                    if (spot.getX() != null && spot.getY() != null) {
                        double dx = userX - spot.getX();
                        double dy = userY - spot.getY();
                        spot.setDistance(Math.sqrt(dx * dx + dy * dy));
                    }
                })
                .sorted(Comparator.comparing(Spot::getDistance,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }
}
