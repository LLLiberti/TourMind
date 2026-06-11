package com.hmdp.rag;

import com.hmdp.entity.Spot;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 每次 RAG 请求的检索上下文（ThreadLocal 持有）
 *
 * <p>用途：HybridDocumentRetriever 检索完成后将 Spot 列表写入，
 * SpotQAServiceImpl 从同一个 ThreadLocal 读取，用于构建响应的 recommendedSpots DTO。</p>
 *
 * <p>线程安全：Spring MVC 同步模型下每个请求一个线程，ThreadLocal 天然隔离。
 * 若迁移到 WebFlux 需改用 Reactor Context。</p>
 */
public class RetrievalContext {

    /** 用户经度（用于距离排序） */
    private Double userX;

    /** 用户纬度（用于距离排序） */
    private Double userY;

    /** 检索阶段查到的 Spot 实体列表（已排序） */
    private List<Spot> retrievedSpots = Collections.emptyList();

    /** spotId → 距离（公里），仅在用户提供坐标时填充，不污染 Spot 实体 */
    private Map<Long, Double> spotDistances = Collections.emptyMap();

    /** CRAG 检索评估结果（CONFIDENT / AMBIGUOUS / INSUFFICIENT） */
    private String retrievalConfidence = "CONFIDENT";

    /** 检索最高相似度分数 */
    private double maxRetrievalScore = 0.0;

    // ==================== Getters & Setters ====================

    public Double getUserX() {
        return userX;
    }

    public void setUserX(Double userX) {
        this.userX = userX;
    }

    public Double getUserY() {
        return userY;
    }

    public void setUserY(Double userY) {
        this.userY = userY;
    }

    public List<Spot> getRetrievedSpots() {
        return retrievedSpots;
    }

    public void setRetrievedSpots(List<Spot> retrievedSpots) {
        this.retrievedSpots = retrievedSpots != null ? retrievedSpots : Collections.emptyList();
    }

    public String getRetrievalConfidence() {
        return retrievalConfidence;
    }

    public void setRetrievalConfidence(String retrievalConfidence) {
        this.retrievalConfidence = retrievalConfidence;
    }

    public Map<Long, Double> getSpotDistances() {
        return spotDistances;
    }

    public void setSpotDistances(Map<Long, Double> spotDistances) {
        this.spotDistances = spotDistances != null ? spotDistances : Collections.emptyMap();
    }

    public double getMaxRetrievalScore() {
        return maxRetrievalScore;
    }

    public void setMaxRetrievalScore(double maxRetrievalScore) {
        this.maxRetrievalScore = maxRetrievalScore;
    }
}
