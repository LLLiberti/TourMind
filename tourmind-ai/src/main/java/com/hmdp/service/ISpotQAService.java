package com.hmdp.service;

import com.hmdp.dto.Result;

/**
 * 智能景点问答服务
 */
public interface ISpotQAService {

    /**
     * 根据用户问题返回推荐景点（支持多轮对话）
     * @param userId 用户 ID
     * @param sessionId 会话 ID（用于多轮对话上下文，可选）
     * @param question 用户问题，如"附近有什么适合约会的餐厅？"
     * @param userX 用户经度（可选）
     * @param userY 用户纬度（可选）
     * @param limit 返回数量限制
     * @return 推荐结果
     */
    Result answerSpotQuestion(Long userId, String sessionId, String question, Double userX, Double userY, int limit);

    /**
     * 根据景点 ID 进行问答（支持多轮对话）
     * @param userId 用户 ID
     * @param sessionId 会话 ID（用于多轮对话上下文，可选）
     * @param spotId 景点 ID
     * @param question 用户问题
     * @return 问答结果
     */
    Result answerQuestionAboutSpot(Long userId, String sessionId, Long spotId, String question);

    /**
     * 清除会话历史
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     */
    void clearConversation(Long userId, String sessionId);

    /**
     * 获取会话信息
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 会话信息
     */
    com.hmdp.dto.Result getConversationInfo(Long userId, String sessionId);

    /**
     * 获取用户所有会话
     * @param userId 用户 ID
     * @return 会话列表
     */
    com.hmdp.dto.Result getUserConversations(Long userId);
}
