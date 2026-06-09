package com.hmdp.rag;

import com.hmdp.entity.Spot;

import java.util.Collections;
import java.util.List;

/**
 * 每次 RAG 请求的检索上下文（ThreadLocal 持有）
 *
 * <p>用途：SpotDocumentRetriever 检索完成后将 Spot 列表写入，
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
}
