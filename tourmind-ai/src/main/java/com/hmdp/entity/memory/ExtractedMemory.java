package com.hmdp.entity.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * LLM 从多轮对话中批量提取的结构化记忆（v3 批量模式）。
 *
 * <p>由 {@code MemoryExtractor.extractBatch()} 生成，供 {@code MemoryCoordinator}
 * 分发到 MySQL（结构化偏好 + 会话摘要）和 Qdrant（非结构化知识）。</p>
 *
 * <p>提取粒度：每 10 轮或 Session 结束批量提取一次，而非每轮单独提取。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedMemory {

    /**
     * 用户画像更新 — key=字段名, value=新值。
     * null 值的 key 表示无变化，不会被写入 MySQL。
     */
    @Builder.Default
    private Map<String, Object> profileUpdates = Collections.emptyMap();

    /**
     * 新学到的事实/偏好知识（从整段对话中提取，非单轮粒度）。
     */
    @Builder.Default
    private List<MemoryFact> newFacts = Collections.emptyList();

    /**
     * 会话摘要（批量提取模式下的多轮对话摘要，替代 v1 的单轮 interactionEvent）。
     */
    private ConversationSummaryResult conversationSummary;

    /** 是否有画像更新 */
    public boolean hasProfileUpdates() {
        return profileUpdates != null && !profileUpdates.isEmpty();
    }

    /** 是否有新事实 */
    public boolean hasNewFacts() {
        return newFacts != null && !newFacts.isEmpty();
    }

    /** 是否有会话摘要 */
    public boolean hasConversationSummary() {
        return conversationSummary != null
                && conversationSummary.getSummary() != null
                && !conversationSummary.getSummary().isBlank();
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
    public static class ConversationSummaryResult {
        /** 对话主题（一句话概括） */
        private String topic;
        /** 累积摘要文本 */
        private String summary;
        /** 用户目标/意图 */
        private String userGoal;
        /** 涉及的实体（景点名/地名） */
        @Builder.Default
        private List<String> discussedEntities = Collections.emptyList();
        /** 当前焦点（用户最近在关注什么） */
        private String currentFocus;
        /** 尚未解决的问题 */
        @Builder.Default
        private List<String> unresolvedQuestions = Collections.emptyList();
    }
}
