package com.hmdp.entity.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 记忆事件实体 — MySQL user_memory_event 表映射。
 *
 * <p>每一条记忆变更（偏好更新/事实学习/交互摘要）产生一条事件记录，
 * 提供可追溯性和审计能力。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEvent {

    private Long id;
    private Long userId;

    /** 事件类型: PROFILE_UPDATE / FACT_LEARNED / INTERACTION_SUMMARY */
    private String eventType;

    /** 人类可读的摘要 */
    private String summary;

    /** 变更详情 JSON（PROFILE_UPDATE 时记录） */
    private String changeDetail;

    /** 来源会话 ID */
    private String sourceConversationId;

    /** 重要性 1-5 */
    private Integer importance;

    /** Qdrant 中对应的 memory_id */
    private String qdrantMemoryId;

    private LocalDateTime createdAt;

    // ==================== 事件类型常量 ====================

    public static final String TYPE_PROFILE_UPDATE = "PROFILE_UPDATE";
    public static final String TYPE_FACT_LEARNED = "FACT_LEARNED";
    public static final String TYPE_INTERACTION_SUMMARY = "INTERACTION_SUMMARY";
}
