package com.hmdp.agent;

import com.hmdp.config.RagConfig;
import com.hmdp.rag.retrieval.HybridDocumentRetriever;
import com.hmdp.service.ISpotToolService;
import com.hmdp.service.IWeatherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * ReACT Agent 循环 — 手动编排 Thought→Action→Observation 迭代。
 *
 * <h3>职责</h3>
 * <ol>
 *   <li>构建初始消息列表（系统提示 + 对话历史 + 用户问题）</li>
 *   <li>注册工具定义（searchKnowledgeBase / getSpotPrice / getSpotVouchers / checkVoucherStock / checkWeather）</li>
 *   <li>通过 ChatClient 循环调用 LLM：检查 tool_calls → 手动执行 → 追加 Observation → 重复</li>
 *   <li>达到最大迭代或 LLM 输出纯文本时返回最终回答</li>
 *   <li>更新 ChatMemory 持久化本轮对话</li>
 * </ol>
 *
 * <h3>为什么用 ChatClient + 手动循环？</h3>
 * <p>Spring AI 1.0 的 ChatClient 内部 tool calling 是单轮黑盒，无法在每步注入置信度反馈
 * 或记录 Trace。设置 {@code internalToolExecutionEnabled=false} +
 * 使用 {@code .call().chatResponse()} 获取原始响应，
 * 在应用层手动循环即可获得完整的可观察性和控制力。</p>
 */
@Slf4j
@Service
public class ReActAgentLoop {

    /** 无 Advisor 的 ChatClient，用于手动 Agent 循环 */
    private final ChatClient agentClient;

    @Resource
    private ChatMemory chatMemory;

    @Resource
    private SearchKnowledgeBaseTool searchTool;

    @Resource
    private ISpotToolService spotToolService;

    @Resource
    private IWeatherService weatherService;

    @Resource
    private HybridDocumentRetriever retriever;

    @Resource
    private RagConfig ragConfig;

    @Resource
    private PlannerAgent planner;

    public ReActAgentLoop(@Qualifier("deepSeekChatModel") ChatModel chatModel) {
        this.agentClient = ChatClient.create(chatModel);
    }

    // ==================== 工具定义 ====================

    private List<ToolCallback> toolCallbacks;

    private List<ToolCallback> getToolCallbacks() {
        if (toolCallbacks == null) {
            toolCallbacks = List.of(
                    createTool("searchKnowledgeBase",
                            "在景点知识库中搜索景点信息。需要查询景点的介绍、位置、评分、开放时间等静态信息时调用。" +
                            "参数 query 为检索关键词串。",
                            """
                            {"type":"object","properties":{"query":{"type":"string","description":"检索关键词串"}},"required":["query"]}""",
                            searchTool::execute),
                    createTool("getSpotPrice",
                            "获取指定景点的门票实时价格。spotId 为整数景点ID。",
                            """
                            {"type":"object","properties":{"spotId":{"type":"integer","description":"景点ID"}},"required":["spotId"]}""",
                            args -> spotToolService.getSpotPrice(parseLongArg(args, "spotId"))),
                    createTool("getSpotVouchers",
                            "获取指定景点当前可用的优惠券/折扣活动。spotId 为整数景点ID。",
                            """
                            {"type":"object","properties":{"spotId":{"type":"integer","description":"景点ID"}},"required":["spotId"]}""",
                            args -> spotToolService.getSpotVouchers(parseLongArg(args, "spotId"))),
                    createTool("checkVoucherStock",
                            "检查指定优惠券的实时库存。voucherId 为整数优惠券ID。",
                            """
                            {"type":"object","properties":{"voucherId":{"type":"integer","description":"优惠券ID"}},"required":["voucherId"]}""",
                            args -> spotToolService.checkVoucherStock(parseLongArg(args, "voucherId"))),
                    createTool("checkWeather",
                            "查询指定地点的实时天气。location 为地名，lat/lon 为可选坐标（double）。",
                            """
                            {"type":"object","properties":{"location":{"type":"string","description":"地名"},"lat":{"type":"number","description":"纬度"},"lon":{"type":"number","description":"经度"}},"required":["location"]}""",
                            args -> weatherService.getWeather(
                                    parseStringArg(args, "location"),
                                    parseDoubleArg(args, "lat"),
                                    parseDoubleArg(args, "lon")))
            );
        }
        return toolCallbacks;
    }

    private static ToolCallback createTool(String name, String description,
                                            String inputSchema, Function<String, String> handler) {
        ToolDefinition def = DefaultToolDefinition.builder()
                .name(name).description(description).inputSchema(inputSchema).build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String toolInput) { return handler.apply(toolInput); }
        };
    }

    // ==================== JSON 参数解析 ====================

    private static Long parseLongArg(String json, String key) {
        String val = extractJsonValue(json, key);
        if (val == null) return null;
        try { return Long.valueOf(val); } catch (NumberFormatException e) { return null; }
    }

    private static Double parseDoubleArg(String json, String key) {
        String val = extractJsonValue(json, key);
        if (val == null) return null;
        try { return Double.valueOf(val); } catch (NumberFormatException e) { return null; }
    }

    private static String parseStringArg(String json, String key) {
        return extractJsonValue(json, key);
    }

    /** 从简单 JSON {"key":"value"} 中提取字符串/数字值 */
    private static String extractJsonValue(String json, String key) {
        if (json == null || json.isBlank()) return null;
        String searchKey = "\"" + key + "\"";
        int ki = json.indexOf(searchKey);
        if (ki < 0) return null;
        int ci = json.indexOf(':', ki + searchKey.length());
        if (ci < 0) return null;
        String after = json.substring(ci + 1).trim();
        if (after.startsWith("\"")) {
            int eq = after.indexOf('"', 1);
            return eq > 0 ? after.substring(1, eq) : after.substring(1);
        }
        int end = 0;
        while (end < after.length() && (Character.isDigit(after.charAt(end))
                || after.charAt(end) == '.' || after.charAt(end) == '-'
                || after.charAt(end) == 'e' || after.charAt(end) == 'E')) {
            end++;
        }
        return end > 0 ? after.substring(0, end) : after;
    }

    // ==================== Planner 增强入口 ====================

    /**
     * Planner 增强的 Agent 执行 — 复杂 query 分解为子任务并行执行。
     *
     * @param question       用户问题
     * @param conversationId 会话 ID
     * @param userX          用户经度（可选）
     * @param userY          用户纬度（可选）
     * @return AgentResult
     */
    public AgentResult thinkAndActWithPlan(String question, String conversationId,
                                            Double userX, Double userY) {
        RagConfig.PlannerConfig plannerConfig = ragConfig.getAgent().getPlanner();
        if (!plannerConfig.isEnabled()) {
            return thinkAndAct(question, conversationId, userX, userY);
        }

        // 1. 尝试分解
        AgentPlan plan = planner.decompose(question);

        if (!plan.isComplex()) {
            log.debug("Planner: 简单 query，走原 ReACT 循环");
            return thinkAndAct(question, conversationId, userX, userY);
        }

        log.info("Planner: 复杂 query，分解为 {} 个子任务", plan.getSubTasks().size());

        // 2. 分层并行执行子任务
        Map<String, String> subResults = executeSubTasks(plan, conversationId, userX, userY,
                plannerConfig);

        // 3. 综合
        List<String> results = plan.getSubTasks().stream()
                .map(t -> subResults.getOrDefault(t.getId(), "子任务未完成"))
                .toList();
        String finalAnswer = planner.synthesize(question, plan.getSubTasks(), results);

        AgentTrace trace = new AgentTrace();
        trace.setTotalIterations(plan.getSubTasks().size());
        return new AgentResult(finalAnswer, trace);
    }

    /**
     * 分层并行执行子任务 — 同层无依赖的子任务并发执行。
     */
    private Map<String, String> executeSubTasks(AgentPlan plan, String conversationId,
                                                 Double userX, Double userY,
                                                 RagConfig.PlannerConfig pc) {
        Map<String, String> results = new ConcurrentHashMap<>();
        Set<String> completed = ConcurrentHashMap.newKeySet();
        List<AgentPlan.SubTask> remaining = new ArrayList<>(plan.getSubTasks());

        while (!remaining.isEmpty()) {
            // 找出当前可执行（依赖已满足）的子任务
            List<AgentPlan.SubTask> ready = remaining.stream()
                    .filter(t -> t.getDependsOn().stream().allMatch(completed::contains))
                    .toList();

            if (ready.isEmpty()) {
                log.warn("Planner: 循环依赖或依赖无法满足，跳出");
                break;
            }

            log.debug("Planner: 并行执行 {} 个子任务", ready.size());

            // 并行执行当前层
            CompletableFuture.allOf(ready.stream()
                    .map(t -> CompletableFuture.supplyAsync(() -> {
                        try {
                            // 注入前序结果作为上下文
                            String ctx = buildSubContext(t, results);
                            String taskQ = ctx.isEmpty() ? t.getQuestion()
                                    : ctx + "\n" + t.getQuestion();

                            // 限制子任务 ReACT 迭代次数
                            int savedMax = ragConfig.getAgent().getMaxIterations();
                            long savedTimeout = ragConfig.getAgent().getTimeoutMs();
                            ragConfig.getAgent().setMaxIterations(pc.getMaxSubIterations());
                            ragConfig.getAgent().setTimeoutMs(pc.getSubTimeoutMs());

                            AgentResult r = thinkAndAct(taskQ, conversationId, userX, userY);

                            ragConfig.getAgent().setMaxIterations(savedMax);
                            ragConfig.getAgent().setTimeoutMs(savedTimeout);

                            results.put(t.getId(), r.answer());
                            return r.answer();
                        } catch (Exception e) {
                            log.error("子任务 {} 执行失败: {}", t.getId(), e.getMessage());
                            results.put(t.getId(), "子任务失败: " + e.getMessage());
                            return results.get(t.getId());
                        }
                    }))
                    .toArray(CompletableFuture[]::new)
            ).join();

            ready.forEach(t -> completed.add(t.getId()));
            remaining.removeAll(ready);
        }

        return results;
    }

    /** 构建子任务上下文（从前序结果中） */
    private String buildSubContext(AgentPlan.SubTask task, Map<String, String> results) {
        if (task.getDependsOn().isEmpty()) return "";
        StringBuilder ctx = new StringBuilder("前置信息：\n");
        for (String depId : task.getDependsOn()) {
            String r = results.get(depId);
            if (r != null) {
                ctx.append(r).append("\n");
            }
        }
        return ctx.toString();
    }

    // ==================== 核心循环 ====================

    /**
     * 执行 ReACT Agent 循环。
     *
     * @param question       用户问题（已通过 QueryRouter 分流，非闲聊）
     * @param conversationId 会话 ID
     * @param userX          用户经度（可选）
     * @param userY          用户纬度（可选）
     * @return AgentResult
     */
    public AgentResult thinkAndAct(String question, String conversationId,
                                    Double userX, Double userY) {
        if (userX != null && userY != null) {
            retriever.setUserCoordinates(userX, userY);
        }

        RagConfig.AgentConfig ac = ragConfig.getAgent();
        AgentTrace trace = new AgentTrace();

        List<Message> messages = buildInitialMessages(question, conversationId, ac);
        int persistedIdx = messages.size();

        long startTime = System.currentTimeMillis();
        String finalAnswer = null;

        for (int iter = 0; iter < ac.getMaxIterations(); iter++) {
            if (System.currentTimeMillis() - startTime > ac.getTimeoutMs()) {
                log.warn("Agent 超时 {}ms，终止", ac.getTimeoutMs());
                trace.setHitMaxIterations(true);
                break;
            }

            log.debug("Agent iter {}: {} messages", iter, messages.size());

            // 构建请求选项（每次迭代重新设置 toolCallbacks）
            ToolCallingChatOptions options = new DefaultToolCallingChatOptions();
            options.setToolCallbacks(new ArrayList<>(getToolCallbacks()));
            options.setInternalToolExecutionEnabled(false);

            ChatResponse response;
            try {
                response = agentClient.prompt()
                        .messages(new ArrayList<>(messages))
                        .options(options)
                        .call()
                        .chatResponse();
            } catch (Exception e) {
                log.error("Agent iter {} 调用失败: {}", iter, e.getMessage());
                finalAnswer = "抱歉，AI 服务暂时不可用，请稍后重试。";
                break;
            }

            AssistantMessage assistantMsg = response.getResult().getOutput();

            // 检查 tool_calls
            if (response.hasToolCalls() || assistantMsg.hasToolCalls()) {
                List<AssistantMessage.ToolCall> toolCalls = assistantMsg.getToolCalls();
                log.info("Agent iter {}: {} tool calls", iter, toolCalls.size());
                messages.add(assistantMsg);

                List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
                for (AssistantMessage.ToolCall tc : toolCalls) {
                    AgentStep step = executeToolCall(tc, trace);
                    results.add(new ToolResponseMessage.ToolResponse(
                            tc.id(), tc.name(), step.getContent()));
                }
                messages.add(new ToolResponseMessage(results, Map.of()));
            } else {
                finalAnswer = assistantMsg.getText();
                messages.add(assistantMsg);
                log.info("Agent done at iter {}, answer len={}",
                        iter, finalAnswer != null ? finalAnswer.length() : 0);
                break;
            }
        }

        if (finalAnswer == null) {
            finalAnswer = "抱歉，处理您的问题超时，请尝试简化问题后重试。";
            trace.setHitMaxIterations(true);
        }

        persistToMemory(conversationId, messages, persistedIdx);
        trace.setTotalIterations(trace.getSteps().size());
        return new AgentResult(finalAnswer, trace);
    }

    private AgentStep executeToolCall(AssistantMessage.ToolCall tc, AgentTrace trace) {
        String toolName = tc.name();
        String args = tc.arguments();
        long start = System.currentTimeMillis();

        String result;
        try {
            result = executeToolByName(toolName, args);
        } catch (Exception e) {
            log.error("工具 {} 异常: {}", toolName, e.getMessage());
            result = "工具执行失败: " + e.getMessage();
        }
        long duration = System.currentTimeMillis() - start;

        // TOOL_CALL step
        trace.getSteps().add(AgentStep.builder()
                .stepNumber(trace.getSteps().size()).type("TOOL_CALL")
                .toolName(toolName).content(truncate(args, 300)).durationMs(duration).build());
        // OBSERVATION step
        AgentStep obs = AgentStep.builder()
                .stepNumber(trace.getSteps().size()).type("OBSERVATION")
                .toolName(toolName).content(truncate(result, 500)).durationMs(duration).build();
        trace.getSteps().add(obs);
        return obs;
    }

    private String executeToolByName(String name, String args) {
        for (ToolCallback cb : getToolCallbacks()) {
            if (cb.getToolDefinition().name().equals(name)) {
                return cb.call(args);
            }
        }
        return "未知工具: " + name;
    }

    // ==================== 消息构建 ====================

    private List<Message> buildInitialMessages(String question, String conversationId,
                                                RagConfig.AgentConfig ac) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(ac)));

        // 加载历史（过滤系统消息）
        if (conversationId != null && !conversationId.isEmpty()) {
            List<Message> history = chatMemory.get(conversationId);
            if (history != null) {
                for (Message msg : history) {
                    if (msg.getMessageType() != MessageType.SYSTEM) {
                        messages.add(msg);
                    }
                }
                log.debug("Agent: loaded {} history messages", history.size());
            }
        }

        messages.add(new UserMessage(question));
        return messages;
    }

    private String buildSystemPrompt(RagConfig.AgentConfig ac) {
        return ac.getSystemPrompt() + "\n\n" +
               "【约束】单次对话工具调用总数不超过 " + ac.getMaxIterations() + " 次。";
    }

    // ==================== ChatMemory 持久化 ====================

    private void persistToMemory(String conversationId, List<Message> messages, int persistedIdx) {
        if (conversationId == null || conversationId.isEmpty()) return;
        for (int i = persistedIdx; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg.getMessageType() != MessageType.SYSTEM) {
                chatMemory.add(conversationId, msg);
            }
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
