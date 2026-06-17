package com.hmdp.service.impl;

import com.hmdp.agent.AgentResult;
import com.hmdp.agent.ReActAgentLoop;
import com.hmdp.dto.Result;
import com.hmdp.dto.SpotDTO;
import com.hmdp.entity.Spot;
import com.hmdp.mapper.SpotMapper;
import com.hmdp.memory.MemoryCoordinator;
import com.hmdp.rag.retrieval.HybridDocumentRetriever;
import com.hmdp.rag.router.QueryRouter;
import com.hmdp.service.IConversationService;
import com.hmdp.service.ISpotQAService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能景点问答服务实现 — Agentic RAG 架构。
 *
 * <h3>架构</h3>
 * <ol>
 *   <li><b>Agent 模式问答</b>：ReActAgentLoop（LLM 自主决策检索+工具调用）</li>
 *   <li><b>指定景点问答</b>：ChatClient + MessageChatMemoryAdvisor（手工构建上下文，无需检索）</li>
 * </ol>
 *
 * <h3>核心流程</h3>
 * <pre>
 * answerSpotQuestion(userId, sessionId, question, x, y):
 *   → 委托到 answerSpotQuestionAgent:
 *     1. 获取/创建会话 → QueryRouter 分流（闲聊跳过）
 *     2. ReActAgentLoop.thinkAndActWithPlan() → Planner 分解 → ReACT 循环 → 工具调用
 *     3. 从 HybridDocumentRetriever 提取 Spots → 构建 DTO
 *     4. finally 清理 ThreadLocal
 *
 * answerQuestionAboutSpot(userId, sessionId, spotId, question):
 *   1. 获取/创建会话
 *   2. DB 查询指定 Spot → 手动构建上下文 prompt
 *   3. ChatClient.prompt().user(contextPrompt).call()
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

    @Resource
    private QueryRouter queryRouter;

    @Resource
    private ReActAgentLoop reActAgentLoop;

    @Resource
    private MemoryCoordinator memoryCoordinator;

    // ==================== 普通问答 → 统一走 Agent 路径 ====================

    @Override
    public Result answerSpotQuestion(Long userId, String sessionId, String question,
                                      Double userX, Double userY, int limit) {
        // 统一委托到 Agent 模式 — RAG 检索和工具调用均由 LLM 自主决策
        return answerSpotQuestionAgent(userId, sessionId, question, userX, userY, limit);
    }

    // ==================== Agent 模式问答 ====================

    @Override
    public Result answerSpotQuestionAgent(Long userId, String sessionId, String question,
                                          Double userX, Double userY, int limit) {
        if (question == null || question.isBlank()) {
            return Result.fail("问题不能为空");
        }

        String conversationId = conversationService.getOrCreateConversation(userId, sessionId);

        // Adaptive 分流 — 闲聊跳过 Agent，复用现有闲聊路径
        QueryRouter.Category category = queryRouter.classify(question);
        log.info("Agent 分流: query='{}' → category={}", question, category);

        if (category == QueryRouter.Category.CHITCHAT) {
            try {
                return answerChitchat(question, conversationId, limit);
            } finally {
                spotDocumentRetriever.clearContext();
            }
        }

        try {
            // ===== Phase 1: Pre-Task — 读取长期记忆 =====
            String memoryContext = memoryCoordinator.buildMemoryContext(userId, question);
            if (!memoryContext.isEmpty()) {
                reActAgentLoop.setMemoryContext(memoryContext);
            }

            // 执行 ReACT Agent 循环
            AgentResult agentResult = reActAgentLoop.thinkAndActWithPlan(
                    question, conversationId, userX, userY);
            String answer = agentResult.answer();

            log.info("Agent trace: {} steps, hitMaxIterations={}",
                    agentResult.trace().getTotalIterations(),
                    agentResult.trace().isHitMaxIterations());

            // CRAG 评估 — 从 ThreadLocal 读取（Agent 调用 searchKnowledgeBase 后写入）
            String confidence = spotDocumentRetriever.getLastRetrievalConfidence();
            if ("INSUFFICIENT".equals(confidence)) {
                answer = "⚠️ 以下信息基于一般知识，建议以景区官方信息为准。\n\n" + answer;
            }

            conversationService.incrementMessageCount(conversationId);

            // ===== Phase 3: Post-Task — 异步写入长期记忆 =====
            memoryCoordinator.extractAndPersist(userId, question, answer,
                    agentResult.trace().getSteps());

            // 构建响应
            List<Spot> retrievedSpots = spotDocumentRetriever.getLastRetrievedSpots();
            Map<Long, Double> distanceMap = spotDocumentRetriever.getLastSpotDistances();
            List<SpotDTO> spotDTOs = retrievedSpots.stream()
                    .limit(limit)
                    .map(spot -> {
                        SpotDTO dto = SpotDTO.from(spot);
                        Double dist = distanceMap.get(spot.getId());
                        if (dist != null) dto.setDistance(dist);
                        return dto;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("answer", answer);
            resultMap.put("sessionId", conversationId);
            resultMap.put("recommendedSpots", spotDTOs);
            resultMap.put("category", category.name());
            resultMap.put("queryComplexity", spotDocumentRetriever.getLastQueryComplexity());
            resultMap.put("retrievalConfidence", confidence != null ? confidence : "UNKNOWN");
            resultMap.put("agentTrace", agentResult.trace());
            return Result.ok(resultMap);

        } finally {
            reActAgentLoop.clearMemoryContext();
            spotDocumentRetriever.clearContext();
        }
    }

    /**
     * 闲聊/问候 → 直接 LLM 回答，完全跳过 RAG 检索管线。
     */
    private Result answerChitchat(String question, String conversationId, int limit) {
        String answer = chatClient.prompt()
                .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                .user("请简洁友好地回应以下用户消息（你是景点推荐助手）：" + question)
                .call()
                .content();

        conversationService.incrementMessageCount(conversationId);

        return Result.ok(Map.of(
                "answer", answer,
                "sessionId", conversationId,
                "recommendedSpots", Collections.emptyList(),
                "category", "CHITCHAT",
                "retrievalConfidence", "SKIPPED"
        ));
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
     * 构建景点上下文文本（仅用于指定景点问答场景）。
     *
     * <p><b>注意：不包含门票价格。</b>价格等实时数据由 Function Calling 工具提供。</p>
     */
    private String buildContextFromSpots(List<Spot> spots) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < spots.size(); i++) {
            Spot spot = spots.get(i);
            sb.append(String.format("【景点%d】\n", i + 1));
            sb.append(String.format("景点ID：%d\n", spot.getId()));
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
