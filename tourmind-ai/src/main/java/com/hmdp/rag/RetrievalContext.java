package com.hmdp.rag;

import com.hmdp.entity.Spot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 每次 RAG 请求的检索上下文（ThreadLocal 持有）
 *
 * <p>用途：贯穿整个 RAG 管线（路由→压缩→改写→检索→评估→生成），
 * 各组件通过 ThreadLocal 读写上下文信息，避免在方法签名中层层传递。</p>
 *
 * <p>线程安全：Spring MVC 同步模型下每个请求一个线程，ThreadLocal 天然隔离。
 * 若迁移到 WebFlux 需改用 Reactor Context。</p>
 */
public class RetrievalContext {

    private static final ThreadLocal<RetrievalContext> HOLDER = new ThreadLocal<>();

    /** 获取当前线程的检索上下文，若不存在则创建 */
    public static RetrievalContext current() {
        RetrievalContext ctx = HOLDER.get();
        if (ctx == null) {
            ctx = new RetrievalContext();
            HOLDER.set(ctx);
        }
        return ctx;
    }

    /** 清理当前线程上下文 */
    public static void clear() {
        HOLDER.remove();
    }

    // ==================== 用户位置 ====================

    /** 用户经度（用于距离排序） */
    private Double userX;

    /** 用户纬度（用于距离排序） */
    private Double userY;

    // ==================== QueryRouter 分流结果 ====================

    /** 查询分类 */
    private String queryCategory = "KNOWLEDGE";

    /** 查询复杂度（1=极简, 5=极复杂） */
    private int queryComplexity = 2;

    // ==================== 检索结果 ====================

    /** 检索阶段查到的 Spot 实体列表（已排序） */
    private List<Spot> retrievedSpots = Collections.emptyList();

    /** spotId → 距离（公里），仅在用户提供坐标时填充，不污染 Spot 实体 */
    private Map<Long, Double> spotDistances = Collections.emptyMap();

    /** CRAG 检索评估结果（CONFIDENT / AMBIGUOUS / INSUFFICIENT） */
    private String retrievalConfidence = "CONFIDENT";

    /** 检索最高相似度分数 */
    private double maxRetrievalScore = 0.0;

    // ==================== CompressionQueryTransformer 上下文 ====================

    /** 前序用户查询（最近 N 条），用于多轮指代消解 */
    private List<String> previousUserQueries = Collections.emptyList();

    /** 前序检索结果中的景点名称（按顺序），用于解析"第二个景点"等序数指代 */
    private List<String> previousSpotNames = Collections.emptyList();

    /** 最近讨论的实体名称，用于解析"它"等代词指代 */
    private String lastDiscussedEntity;

    // ==================== 改写策略 ====================

    /** 查询改写策略（KEYWORD / MULTI_QUERY / HYDE / MULTI_HYDE） */
    private String rewriteStrategy = "KEYWORD";

    /** 检索模式（VECTOR_ONLY / BM25_ONLY / HYBRID_RRF），LLM 可在工具调用时覆盖 */
    private RetrievalMode retrievalMode = RetrievalMode.HYBRID_RRF;

    // ==================== RAG 管线计时（由 SearchKnowledgeBaseTool 写入） ====================

    /** 缓存查询耗时（毫秒） */
    private long cacheQueryMs;
    /** 指代消解耗时（毫秒） */
    private long compressionMs;
    /** 查询改写耗时（毫秒） */
    private long rewriteMs;
    /** 混合检索耗时（毫秒，含 Qdrant/ES/RRF/重排/DB/父文档） */
    private long retrievalMs;
    /** CRAG 纠正耗时（毫秒） */
    private long cragMs;
    /** 格式化输出耗时（毫秒） */
    private long formatMs;
    /** RAG 管线总耗时（毫秒） */
    private long ragTotalMs;

    // ==================== Getters & Setters ====================

    public Double getUserX() { return userX; }
    public void setUserX(Double userX) { this.userX = userX; }

    public Double getUserY() { return userY; }
    public void setUserY(Double userY) { this.userY = userY; }

    public String getQueryCategory() { return queryCategory; }
    public void setQueryCategory(String queryCategory) { this.queryCategory = queryCategory; }

    public int getQueryComplexity() { return queryComplexity; }
    public void setQueryComplexity(int queryComplexity) { this.queryComplexity = queryComplexity; }

    public List<Spot> getRetrievedSpots() { return retrievedSpots; }
    public void setRetrievedSpots(List<Spot> retrievedSpots) {
        this.retrievedSpots = retrievedSpots != null ? retrievedSpots : Collections.emptyList();
    }

    public String getRetrievalConfidence() { return retrievalConfidence; }
    public void setRetrievalConfidence(String retrievalConfidence) {
        this.retrievalConfidence = retrievalConfidence;
    }

    public Map<Long, Double> getSpotDistances() { return spotDistances; }
    public void setSpotDistances(Map<Long, Double> spotDistances) {
        this.spotDistances = spotDistances != null ? spotDistances : Collections.emptyMap();
    }

    public double getMaxRetrievalScore() { return maxRetrievalScore; }
    public void setMaxRetrievalScore(double maxRetrievalScore) { this.maxRetrievalScore = maxRetrievalScore; }

    public List<String> getPreviousUserQueries() { return previousUserQueries; }
    public void setPreviousUserQueries(List<String> previousUserQueries) {
        this.previousUserQueries = previousUserQueries != null ? previousUserQueries : Collections.emptyList();
    }

    public List<String> getPreviousSpotNames() { return previousSpotNames; }
    public void setPreviousSpotNames(List<String> previousSpotNames) {
        this.previousSpotNames = previousSpotNames != null ? previousSpotNames : Collections.emptyList();
    }

    public String getLastDiscussedEntity() { return lastDiscussedEntity; }
    public void setLastDiscussedEntity(String lastDiscussedEntity) {
        this.lastDiscussedEntity = lastDiscussedEntity;
    }

    public String getRewriteStrategy() { return rewriteStrategy; }
    public void setRewriteStrategy(String rewriteStrategy) { this.rewriteStrategy = rewriteStrategy; }

    public RetrievalMode getRetrievalMode() { return retrievalMode; }
    public void setRetrievalMode(RetrievalMode retrievalMode) { this.retrievalMode = retrievalMode; }

    // ==================== RAG 管线计时 Getters & Setters ====================

    public long getCacheQueryMs() { return cacheQueryMs; }
    public void setCacheQueryMs(long cacheQueryMs) { this.cacheQueryMs = cacheQueryMs; }

    public long getCompressionMs() { return compressionMs; }
    public void setCompressionMs(long compressionMs) { this.compressionMs = compressionMs; }

    public long getRewriteMs() { return rewriteMs; }
    public void setRewriteMs(long rewriteMs) { this.rewriteMs = rewriteMs; }

    public long getRetrievalMs() { return retrievalMs; }
    public void setRetrievalMs(long retrievalMs) { this.retrievalMs = retrievalMs; }

    public long getCragMs() { return cragMs; }
    public void setCragMs(long cragMs) { this.cragMs = cragMs; }

    public long getFormatMs() { return formatMs; }
    public void setFormatMs(long formatMs) { this.formatMs = formatMs; }

    public long getRagTotalMs() { return ragTotalMs; }
    public void setRagTotalMs(long ragTotalMs) { this.ragTotalMs = ragTotalMs; }

    /**
     * 根据查询分类 + 复杂度推导默认检索模式。
     * <p>由 QueryRouter 在 classify() 时调用，写入上下文。LLM 后续可通过
     * searchKnowledgeBase 的 retrievalMode 参数覆盖。</p>
     */
    public static RetrievalMode deriveDefaultRetrievalMode(String category, int complexity) {
        if (category == null) return RetrievalMode.HYBRID_RRF;
        return switch (category) {
            case "FACT_LOOKUP"   -> complexity <= 2 ? RetrievalMode.VECTOR_ONLY : RetrievalMode.HYBRID_RRF;
            case "TICKET_REFUND" -> RetrievalMode.BM25_ONLY;
            case "RECOMMENDATION", "COMPARISON", "COMPLEX" -> RetrievalMode.HYBRID_RRF;
            default              -> complexity <= 2 ? RetrievalMode.VECTOR_ONLY : RetrievalMode.HYBRID_RRF;
        };
    }
}
