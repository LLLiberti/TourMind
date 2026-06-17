package com.hmdp.entity.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户画像实体 — MySQL user_profile 表映射。
 *
 * <p>每用户一行，存储从对话中提取的结构化偏好。
 * JSON 字段（preferredSpotTypes 等）使用字符串存储，Service 层负责序列化/反序列化。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {

    private Long id;
    private Long userId;

    /** 偏好景点类型 JSON 数组: ["自然风光","历史古迹"] */
    private String preferredSpotTypes;

    /** 偏好区域 JSON 数组: ["西湖区","上城区"] */
    private String preferredAreas;

    /** 预算水平: LOW / MEDIUM / HIGH */
    private String budgetLevel;

    /** 出行方式偏好 JSON 对象: {"亲子":true,"摄影":true} */
    private String travelStyle;

    /** 偏好时间段 */
    private String preferredTime;

    /** 最低评分偏好 (0-100) */
    private Double minRating;

    /** 常浏览景点ID JSON 数组 */
    private String frequentlyViewedSpots;

    /** 累计对话次数 */
    private Integer totalConversations;

    /** 累计问题数 */
    private Integer totalQuestions;

    /** 上次记忆提取时间 */
    private LocalDateTime lastExtractedAt;

    /** 最后活跃时间 */
    private LocalDateTime lastActiveAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 是否有有效偏好数据（至少一个偏好字段非空） */
    public boolean hasPreferences() {
        return (preferredSpotTypes != null && !preferredSpotTypes.equals("[]"))
                || (preferredAreas != null && !preferredAreas.equals("[]"))
                || budgetLevel != null
                || (travelStyle != null && !travelStyle.equals("{}"))
                || preferredTime != null
                || minRating != null;
    }

    /** 构建注入到 LLM 系统提示的上下文文本 */
    public String toContextText() {
        if (!hasPreferences()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("【用户画像】\n");
        if (preferredSpotTypes != null && !preferredSpotTypes.equals("[]")) {
            sb.append("- 偏好景点类型: ").append(formatJsonArray(preferredSpotTypes)).append("\n");
        }
        if (preferredAreas != null && !preferredAreas.equals("[]")) {
            sb.append("- 偏好区域: ").append(formatJsonArray(preferredAreas)).append("\n");
        }
        if (budgetLevel != null) {
            sb.append("- 预算水平: ").append(budgetLevel.equals("LOW") ? "经济型"
                    : budgetLevel.equals("HIGH") ? "高端型" : "中等").append("\n");
        }
        if (travelStyle != null && !travelStyle.equals("{}")) {
            sb.append("- 出行方式: ").append(formatTravelStyle(travelStyle)).append("\n");
        }
        if (preferredTime != null) {
            sb.append("- 偏好时间: ").append(preferredTime).append("\n");
        }
        if (minRating != null) {
            sb.append("- 最低评分要求: ").append(minRating).append(" 分\n");
        }
        return sb.toString();
    }

    private String formatJsonArray(String json) {
        return json.replaceAll("[\\[\\]\"]", "").replace(",", "、");
    }

    private String formatTravelStyle(String json) {
        return json.replaceAll("[\\{\\}\"]", "")
                .replace("true", "✓").replace("false", "✗")
                .replace(",", " ");
    }
}
