package com.hmdp.entity.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * LLM 从一轮对话中提取的结构化记忆。
 *
 * <p>由 {@code MemoryExtractor} 生成，供 {@code MemoryCoordinator} 分发到
 * MySQL（结构化偏好）和 Qdrant（非结构化知识/事件）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedMemory {

    /**
     * 用户画像更新 — key=字段名, value=新值。
     * null 值的 key 表示无变化，不会被写入 MySQL。
     */@Builder.Default
    private Map<String, Object> profileUpdates = Collections.emptyMap();

    /**
     * 新学到的事实/偏好知识。
     */
    @Builder.Default
    private List<MemoryFact> newFacts = Collections.emptyList();

    /**
     * 本次交互摘要事件。
     */
    private InteractionEvent interactionEvent;

    /** 是否有画像更新 */
    public boolean hasProfileUpdates() {
        return profileUpdates != null && !profileUpdates.isEmpty();
    }

    /** 是否有新事实 */
    public boolean hasNewFacts() {
        return newFacts != null && !newFacts.isEmpty();
    }

    // ==================== 内嵌类型 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryFact {
        /** fact / preference / knowledge */
        private String type;
        /** 人类可读的文本 */
        private String content;
        /** 重要性 1-5 */
        private int importance;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InteractionEvent {
        /** 交互摘要文本 */
        private String summary;
        /** 涉及的实体（景点名/地名） */
        @Builder.Default
        private List<String> entities = Collections.emptyList();
        /** 交互结果: recommended / answered / deferred */
        private String outcome;
    }
}
