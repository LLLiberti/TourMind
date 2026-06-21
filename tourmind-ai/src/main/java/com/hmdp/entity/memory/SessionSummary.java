package com.hmdp.entity.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话摘要实体 — MySQL session_summary 表映射（v3.1 会话级 upsert 模型）。
 *
 * <p>每个 Session 仅一行，随对话推进更新（upsert），而非每 10 轮新增一行。
 * MemoryContextBuilder 在 Pre-Task 阶段读取注入 SystemPrompt。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionSummary {

    /** 会话 ID（主键，与 ChatMemory conversationId 一一对应） */
    private String sessionId;

    /** 用户 ID */
    private Long userId;

    /** 对话主题（一句话概括） */
    private String topic;

    /** 对话摘要文本（LLM 生成的累积摘要） */
    private String summary;

    /** 用户目标/意图 */
    private String userGoal;

    /** JSON 数组：涉及的景点/地名 */
    private String discussedEntities;

    /** 当前焦点（用户最近在关注什么） */
    private String currentFocus;

    /** JSON 数组：尚未解决的问题 */
    private String unresolvedQuestions;

    /** 已处理的累计轮次数 */
    private Integer roundCount;

    /** 最后更新时间 */
    private LocalDateTime updatedTime;

    // ==================== 便捷方法 ====================

    /**
     * 构建注入到 LLM SystemPrompt 的上下文文本行。
     */
    public String toContextLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("- 话题：").append(topic != null ? topic : "未知").append("\n");
        if (summary != null && !summary.isBlank()) {
            sb.append("  摘要：").append(summary).append("\n");
        }
        if (userGoal != null && !userGoal.isBlank()) {
            sb.append("  用户目标：").append(userGoal).append("\n");
        }
        if (currentFocus != null && !currentFocus.isBlank()) {
            sb.append("  当前焦点：").append(currentFocus).append("\n");
        }
        return sb.toString();
    }

    /**
     * 判断是否有有效内容。
     */
    public boolean hasContent() {
        return (summary != null && !summary.isBlank())
                || (topic != null && !topic.isBlank());
    }
}
