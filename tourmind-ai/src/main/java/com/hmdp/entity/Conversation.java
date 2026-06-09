package com.hmdp.entity;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {

    /**
     * 会话 ID
     */
    private String conversationId;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 会话创建时间
     */
    private LocalDateTime createTime;

    /**
     * 最后交互时间
     */
    private LocalDateTime lastInteractionTime;

    /**
     * 消息数量
     */
    private Integer messageCount;

    /**
     * 是否有效
     */
    private Boolean isValid;

    /**
     * 会话状态
     */
    private ConversationStatus status;

    /**
     * 会话状态枚举
     */
    public enum ConversationStatus {
        /**
         * 活跃
         */
        ACTIVE,
        /**
         * 超时
         */
        TIMEOUT,
        /**
         * 消息数超限
         */
        MESSAGE_LIMIT_EXCEEDED,
        /**
         * 已关闭
         */
        CLOSED
    }

    /**
     * 检查会话是否超时
     * @param timeoutMinutes 超时时间（分钟）
     * @return true-已超时，false-未超时
     */
    public boolean isTimeout(int timeoutMinutes) {
        if (lastInteractionTime == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return now.isAfter(lastInteractionTime.plusMinutes(timeoutMinutes));
    }

    /**
     * 检查消息数是否超限
     * @param maxMessages 最大消息数
     * @return true-超限，false-未超限
     */
    public boolean isMessageLimitExceeded(int maxMessages) {
        return messageCount != null && messageCount >= maxMessages;
    }

    /**
     * 更新最后交互时间
     */
    public void updateLastInteractionTime() {
        this.lastInteractionTime = LocalDateTime.now();
    }

    /**
     * 增加消息数
     */
    public void incrementMessageCount() {
        if (this.messageCount == null) {
            this.messageCount = 0;
        }
        this.messageCount++;
    }
}
