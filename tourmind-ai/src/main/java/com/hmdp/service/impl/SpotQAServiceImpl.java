package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.SpotDTO;
import com.hmdp.entity.Spot;
import com.hmdp.mapper.SpotMapper;
import com.hmdp.rag.retrieval.HybridDocumentRetriever;
import com.hmdp.service.IConversationService;
import com.hmdp.service.ISpotQAService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能景点问答服务实现 — 基于 Spring AI 模块化 RAG 架构
 *
 * <h3>架构</h3>
 * <ol>
 *   <li><b>普通问答（向量检索）</b>：ChatClient + MessageChatMemoryAdvisor + RetrievalAugmentationAdvisor</li>
 *   <li><b>指定景点问答</b>：ChatClient + MessageChatMemoryAdvisor（手工构建上下文，无需检索）</li>
 * </ol>
 *
 * <h3>核心流程</h3>
 * <pre>
 * answerSpotQuestion(userId, sessionId, question, x, y):
 *   1. 获取/创建会话
 *   2. 设置用户坐标 → SpotDocumentRetriever
 *   3. ChatClient.prompt().user(question).call() → Advisor 链自动处理检索+记忆
 *   4. 从 SpotDocumentRetriever 提取 Spots → 构建 DTO
 *   5. finally 清理 ThreadLocal
 *
 * answerQuestionAboutSpot(userId, sessionId, spotId, question):
 *   1. 获取/创建会话
 *   2. DB 查询指定 Spot → 手动构建上下文 prompt
 *   3. ChatClient.prompt().user(contextPrompt).call() → MessageChatMemoryAdvisor 处理记忆
 *   4. 返回结果
 * </pre>
 */
@Slf4j
@Service
public class SpotQAServiceImpl implements ISpotQAService {

    private static final String USER_PROMPT_TEMPLATE = """
        用户问题：{question}

        相关的景点信息：
        {context}

        请根据以上信息回答用户问题，如果涉及距离计算，请根据景点地址和坐标判断。
        """;

    @Resource
    private ChatClient chatClient;

    @Resource
    private SpotMapper spotMapper;

    @Resource
    private IConversationService conversationService;

    @Resource
    private HybridDocumentRetriever spotDocumentRetriever;

    // ==================== 普通问答（RAG 检索） ====================

    @Override
    public Result answerSpotQuestion(Long userId, String sessionId, String question,
                                      Double userX, Double userY, int limit) {
        if (question == null || question.isBlank()) {
            return Result.fail("问题不能为空");
        }

        // 1. 获取或创建会话
        String conversationId = conversationService.getOrCreateConversation(userId, sessionId);

        // 2. 设置用户坐标（SpotDocumentRetriever 内部会进行距离排序）
        spotDocumentRetriever.setUserCoordinates(userX, userY);

        try {
            // 3. 调用 ChatClient — Advisor 链自动处理：
            //    a) MessageChatMemoryAdvisor: 注入对话历史
            //    b) RetrievalAugmentationAdvisor: SpotDocumentRetriever 检索 + ContextualQueryAugmenter 注入上下文
            //    c) SimpleLoggerAdvisor: 日志记录
            //    将用户坐标注入到 prompt 中，使 LLM 能基于位置回答
            String promptText = buildPromptWithCoordinates(question, userX, userY);
            String answer = chatClient.prompt()
                    .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                    .user(promptText)
                    .call()
                    .content();

            // 4. 递增消息计数
            conversationService.incrementMessageCount(conversationId);

            // 5. 从 SpotDocumentRetriever 提取检索到的景点（已排序）
            List<Spot> retrievedSpots = spotDocumentRetriever.getLastRetrievedSpots();

            // 6. 构建响应
            List<SpotDTO> spotDTOs = retrievedSpots.stream()
                    .limit(limit)
                    .map(SpotDTO::from)
                    .collect(Collectors.toList());

            return Result.ok(Map.of(
                    "answer", answer,
                    "sessionId", conversationId,
                    "recommendedSpots", spotDTOs
            ));
        } finally {
            // 7. 清理 ThreadLocal，防止内存泄漏
            spotDocumentRetriever.clearContext();
        }
    }

    // ==================== 指定景点问答（无须 RAG 检索） ====================

    @Override
    public Result answerQuestionAboutSpot(Long userId, String sessionId,
                                           Long spotId, String question) {
        if (question == null || question.isBlank()) {
            return Result.fail("问题不能为空");
        }

        // 1. 获取或创建会话
        String conversationId = conversationService.getOrCreateConversation(userId, sessionId);

        // 2. 获取指定景点
        Spot spot = spotMapper.selectById(spotId);
        if (spot == null) {
            return Result.fail("景点不存在");
        }

        // 3. 手动构建上下文（已知景点，无需向量检索）
        String context = buildContextFromSpots(List.of(spot));
        String promptText = USER_PROMPT_TEMPLATE
                .replace("{question}", question)
                .replace("{context}", context);

        // 4. 调用 ChatClient — MessageChatMemoryAdvisor 自动处理对话记忆
        String answer = chatClient.prompt()
                .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                .user(promptText)
                .call()
                .content();

        // 5. 递增消息计数
        conversationService.incrementMessageCount(conversationId);

        return Result.ok(Map.of(
                "answer", answer,
                "sessionId", conversationId,
                "spot", SpotDTO.from(spot)
        ));
    }

    // ==================== 会话管理（委托给 ConversationService） ====================

    @Override
    public void clearConversation(Long userId, String sessionId) {
        if (userId != null && sessionId != null) {
            conversationService.clearConversation(userId, sessionId);
            log.info("已清除会话历史：userId={}, sessionId={}", userId, sessionId);
        }
    }

    @Override
    public Result getConversationInfo(Long userId, String sessionId) {
        return conversationService.getConversationInfo(userId, sessionId);
    }

    @Override
    public Result getUserConversations(Long userId) {
        return Result.ok(conversationService.getUserConversations(userId));
    }

    // ==================== 内部方法 ====================

    /**
     * 构建包含用户坐标的 prompt 文本，使 LLM 能基于位置回答
     */
    private String buildPromptWithCoordinates(String question, Double userX, Double userY) {
        if (userX != null && userY != null) {
            return String.format("（当前用户位于经度 %.4f、纬度 %.4f 的位置，请优先推荐距离近的景点）%s",
                    userX, userY, question);
        }
        return question;
    }

    /**
     * 构建景点上下文文本（仅用于指定景点问答场景）。
     *
     * <p><b>注意：不包含门票价格。</b>价格等实时数据由 Function Calling 工具提供。</p>
     */
    private String buildContextFromSpots(List<Spot> spots) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < spots.size(); i++) {
            Spot spot = spots.get(i);
            sb.append(String.format("【景点%d】\n", i + 1));
            sb.append(String.format("名称：%s\n", spot.getName()));
            sb.append(String.format("地址：%s %s\n",
                    spot.getArea() != null ? spot.getArea() : "",
                    spot.getAddress() != null ? spot.getAddress() : ""));
            // ticketPrice 不写入上下文 — 实时价格由 Function Calling 工具获取
            sb.append(String.format("评分：%d 分\n", spot.getScore()));
            if (spot.getOpenHours() != null) {
                sb.append(String.format("营业时间：%s\n", spot.getOpenHours()));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

}
