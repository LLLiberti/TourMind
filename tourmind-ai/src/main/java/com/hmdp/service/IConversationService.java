package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Conversation;

import java.util.List;

/**
 * 会话管理服务
 */
public interface IConversationService {

    /**
     * 获取或创建会话
     * @param userId 用户 ID
     * @param sessionId 会话 ID（可选，为空则创建新会话）
     * @return 会话 ID（格式：userId:sessionId）
     */
    String getOrCreateConversation(Long userId, String sessionId);

    /**
     * 检查会话是否有效
     * @param conversationId 会话 ID（格式：userId:sessionId）
     * @return true-有效，false-无效
     */
    boolean isConversationValid(String conversationId);

    /**
     * 获取会话状态
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 会话状态
     */
    Conversation.ConversationStatus getConversationStatus(Long userId, String sessionId);

    /**
     * 获取用户所有会话
     * @param userId 用户 ID
     * @return 会话列表
     */
    List<Conversation> getUserConversations(Long userId);

    /**
     * 清除指定会话
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     */
    void clearConversation(Long userId, String sessionId);

    /**
     * 清除用户所有会话
     * @param userId 用户 ID
     */
    void clearAllUserConversations(Long userId);

    /**
     * 获取会话信息
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 会话信息
     */
    Result getConversationInfo(Long userId, String sessionId);

    /**
     * 递增会话消息计数（每次 LLM 交互后调用）
     * @param conversationId 会话 ID（格式：userId:sessionId）
     */
    void incrementMessageCount(String conversationId);
}
