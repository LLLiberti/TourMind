package com.hmdp.agent;

import com.hmdp.config.RagConfig;
import com.hmdp.rag.RetrievalContext;
import com.hmdp.rag.retrieval.HybridDocumentRetriever;
import com.hmdp.service.ISpotToolService;
import com.hmdp.service.IWeatherService;
import com.hmdp.tool.SearchKnowledgeBaseTool;
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
 * <h3>P0 增强</h3>
 * <ul>
 *   <li><b>子任务重试</b> — 指数退避（1s→4s），最多 2 次</li>
 *   <li><b>Early Stopping</b> — 置信度 CONFIDENT + 信息完整时提前终止</li>
 *   <li><b>自适应迭代</b> — maxIterations/timeout 根据 queryComplexity 调整</li>
 *   <li><b>多轮检索规划</b> — 从对话历史注入上下文到 RetrievalContext，支撑指代消解</li>
 *   <li><b>并行工具调用</b> — 无依赖的多个 tool_calls 并发执行</li>
 *   <li><b>工具去重</b> — 硬约束拦截重复调用</li>
 * </ul>
 */
@Slf4j
@Service
public class ReActAgentLoop {

    /** 子任务最大重试次数 */
    private static final int MAX_RETRY_ATTEMPTS = 2;
    /** 重试基础延迟（毫秒） */
    private static final long RETRY_BASE_DELAY_MS = 1000;
    /** 工具去重：已调用的 (toolName, argsHash) 记录 */
    private static final int MAX_DUPLICATE_CALLS = 2;

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

        // 2. 分层并行执行子任务（带重试）
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
     * 分层并行执行子任务 — 同层无依赖的子任务并发执行，失败自动重试。
     */
    private Map<String, String> executeSubTasks(AgentPlan plan, String conversationId,
                                                 Double userX, Double userY,
                                                 RagConfig.PlannerConfig pc) {
        Map<String, String> results = new ConcurrentHashMap<>();
        Set<String> completed = ConcurrentHashMap.newKeySet();
        List<AgentPlan.SubTask> remaining = new ArrayList<>(plan.getSubTasks());

        while (!remaining.isEmpty()) {
            List<AgentPlan.SubTask> ready = remaining.stream()
                    .filter(t -> t.getDependsOn().stream().allMatch(completed::contains))
                    .toList();

            if (ready.isEmpty()) {
                log.warn("Planner: 循环依赖或依赖无法满足，跳出");
                break;
            }

            log.debug("Planner: 并行执行 {} 个子任务", ready.size());

            CompletableFuture.allOf(ready.stream()
                    .map(t -> CompletableFuture.supplyAsync(() -> {
                        String ctx = buildSubContext(t, results);
                        String taskQ = ctx.isEmpty() ? t.getQuestion()
                                : ctx + "\n" + t.getQuestion();

                        int savedMax = ragConfig.getAgent().getMaxIterations();
                        long savedTimeout = ragConfig.getAgent().getTimeoutMs();
                        ragConfig.getAgent().setMaxIterations(pc.getMaxSubIterations());
                        ragConfig.getAgent().setTimeoutMs(pc.getSubTimeoutMs());

                        // 带重试的子任务执行
                        String answer = executeSubTaskWithRetry(taskQ, conversationId, userX, userY);

                        ragConfig.getAgent().setMaxIterations(savedMax);
                        ragConfig.getAgent().setTimeoutMs(savedTimeout);

                        results.put(t.getId(), answer);
                        return answer;
                    }))
                    .toArray(CompletableFuture[]::new)
            ).join();

            ready.forEach(t -> completed.add(t.getId()));
            remaining.removeAll(ready);
        }

        return results;
    }

    /**
     * 带指数退避重试的子任务执行。
     */
    private String executeSubTaskWithRetry(String question, String conversationId,
                                            Double userX, Double userY) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS + 1; attempt++) {
            try {
                AgentResult r = thinkAndAct(question, conversationId, userX, userY);
                return r.answer();
            } catch (Exception e) {
                lastException = e;
                if (attempt <= MAX_RETRY_ATTEMPTS) {
                    long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1)); // 1s, 2s
                    log.warn("子任务执行失败 (attempt {}), {}ms后重试: {}", attempt, delay, e.getMessage());
                    try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        log.error("子任务重试耗尽: {}", lastException != null ? lastException.getMessage() : "unknown");
        return "子任务失败: " + (lastException != null ? lastException.getMessage() : "未知错误");
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
     * 执行 ReACT Agent 循环（P0 增强版）。
     */
    public AgentResult thinkAndAct(String question, String conversationId,
                                    Double userX, Double userY) {
        if (userX != null && userY != null) {
            retriever.setUserCoordinates(userX, userY);
        }

        RagConfig.AgentConfig ac = ragConfig.getAgent();
        AgentTrace trace = new AgentTrace();

        // 【多轮检索规划】从对话历史注入上下文到 RetrievalContext
        populateRetrievalContextFromHistory(conversationId, question);

        // 【自适应迭代】根据 queryComplexity 调整参数
        int effectiveMaxIter = computeMaxIterations(ac);
        long effectiveTimeout = computeTimeout(ac);

        List<Message> messages = buildInitialMessages(question, conversationId, ac);
        int persistedIdx = messages.size();

        long startTime = System.currentTimeMillis();
        String finalAnswer = null;

        // 工具去重记录：toolName:argsHash → 调用次数
        Map<String, Integer> toolCallHistory = new HashMap<>();
        // 已获取的信息类型列表（用于 early stopping 判断）
        Set<String> acquiredInfoTypes = new LinkedHashSet<>();

        for (int iter = 0; iter < effectiveMaxIter; iter++) {
            if (System.currentTimeMillis() - startTime > effectiveTimeout) {
                log.warn("Agent 超时 {}ms，终止", effectiveTimeout);
                trace.setHitMaxIterations(true);
                break;
            }

            log.debug("Agent iter {}/{}: {} messages", iter + 1, effectiveMaxIter, messages.size());

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
                log.error("Agent iter {} 调用失败: {}", iter + 1, e.getMessage());
                finalAnswer = "抱歉，AI 服务暂时不可用，请稍后重试。";
                break;
            }

            AssistantMessage assistantMsg = response.getResult().getOutput();

            // 检查 tool_calls
            if (response.hasToolCalls() || assistantMsg.hasToolCalls()) {
                List<AssistantMessage.ToolCall> toolCalls = assistantMsg.getToolCalls();
                log.info("Agent iter {}: {} tool calls", iter + 1, toolCalls.size());
                messages.add(assistantMsg);

                List<ToolResponseMessage.ToolResponse> results;
                if (toolCalls.size() > 1) {
                    // 【并行工具调用】无依赖的多个 tool_calls 并发执行
                    results = executeToolCallsParallel(toolCalls, trace, toolCallHistory);
                } else {
                    // 单工具调用：先检查去重
                    AssistantMessage.ToolCall tc = toolCalls.get(0);
                    String dedupKey = dedupKey(tc);
                    Integer callCount = toolCallHistory.getOrDefault(dedupKey, 0);
                    if (callCount >= MAX_DUPLICATE_CALLS) {
                        log.info("Agent: 工具 {} 重复调用已达上限，跳过", tc.name());
                        results = List.of(new ToolResponseMessage.ToolResponse(
                                tc.id(), tc.name(),
                                "[跳过] 该工具相同参数已调用 " + callCount + " 次，请使用已有结果。"));
                    } else {
                        toolCallHistory.put(dedupKey, callCount + 1);
                        AgentStep step = executeToolCall(tc, trace);
                        results = List.of(new ToolResponseMessage.ToolResponse(
                                tc.id(), tc.name(), step.getContent()));
                    }
                }
                messages.add(new ToolResponseMessage(results, Map.of()));

                // 【Early Stopping】检查是否已收集足够信息
                for (AssistantMessage.ToolCall tc : toolCalls) {
                    if ("searchKnowledgeBase".equals(tc.name())) acquiredInfoTypes.add("knowledge");
                    else if ("getSpotPrice".equals(tc.name())) acquiredInfoTypes.add("price");
                    else if ("getSpotVouchers".equals(tc.name())) acquiredInfoTypes.add("voucher");
                    else if ("checkWeather".equals(tc.name())) acquiredInfoTypes.add("weather");
                }
                // 若有知识库+实时信息（或仅知识库且 CONFIDENT），可提前终止
                if (acquiredInfoTypes.contains("knowledge")
                        && "CONFIDENT".equals(retriever.getLastRetrievalConfidence())
                        && (acquiredInfoTypes.size() >= 2 || acquiredInfoTypes.contains("weather")
                            || acquiredInfoTypes.contains("price"))) {
                    log.debug("Agent: early stopping 条件满足，下次迭代可能终止");
                }

            } else {
                finalAnswer = assistantMsg.getText();
                messages.add(assistantMsg);
                log.info("Agent done at iter {}, answer len={}",
                        iter + 1, finalAnswer != null ? finalAnswer.length() : 0);
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

    // ==================== 并行工具调用 ====================

    /**
     * 并行执行多个工具调用（无依赖检测）。
     * <p>策略：不同工具之间默认无依赖 → 全并行。同一工具的多个调用 → 并行（不同参数）。
     * 有依赖的情况（如 getSpotVouchers→checkVoucherStock）由 LLM 自然分两次迭代处理。</p>
     */
    private List<ToolResponseMessage.ToolResponse> executeToolCallsParallel(
            List<AssistantMessage.ToolCall> toolCalls, AgentTrace trace,
            Map<String, Integer> toolCallHistory) {

        List<AgentStep> steps = Collections.synchronizedList(new ArrayList<>());

        List<CompletableFuture<ToolResponseMessage.ToolResponse>> futures = toolCalls.stream()
                .map(tc -> CompletableFuture.supplyAsync(() -> {
                    // 去重检查
                    String dedupKey = dedupKey(tc);
                    int callCount;
                    synchronized (toolCallHistory) {
                        callCount = toolCallHistory.getOrDefault(dedupKey, 0);
                        if (callCount >= MAX_DUPLICATE_CALLS) {
                            log.info("Agent: 工具 {} 重复调用已达上限，跳过（并行）", tc.name());
                            AgentStep skipStep = AgentStep.builder()
                                    .stepNumber(0).type("TOOL_CALL")
                                    .toolName(tc.name()).content("跳过：重复调用")
                                    .durationMs(0).build();
                            steps.add(skipStep);
                            return new ToolResponseMessage.ToolResponse(
                                    tc.id(), tc.name(),
                                    "[跳过] 该工具相同参数已调用 " + callCount + " 次。");
                        }
                        toolCallHistory.put(dedupKey, callCount + 1);
                    }

                    AgentStep step = executeToolCall(tc, trace);
                    steps.add(step);
                    return new ToolResponseMessage.ToolResponse(
                            tc.id(), tc.name(), step.getContent());
                }))
                .toList();

        // 等待全部完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 按原始顺序排序到 trace
        for (AgentStep s : steps) {
            s.setStepNumber(trace.getSteps().size());
            trace.getSteps().add(s);
        }

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
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

        AgentStep tcStep = AgentStep.builder()
                .stepNumber(trace.getSteps().size()).type("TOOL_CALL")
                .toolName(toolName).content(truncate(args, 300)).durationMs(duration).build();
        trace.getSteps().add(tcStep);

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

    // ==================== 自适应迭代 ====================

    /**
     * 根据 queryComplexity 计算实际 maxIterations。
     */
    private int computeMaxIterations(RagConfig.AgentConfig ac) {
        try {
            int complexity = RetrievalContext.current().getQueryComplexity();
            // 复杂度 → 最大迭代: 1→3, 2→5, 3→7, 4→9, 5→10
            int adaptive = switch (complexity) {
                case 1 -> 3;
                case 2 -> 5;
                case 3 -> 7;
                case 4 -> 9;
                default -> ac.getMaxIterations();
            };
            log.debug("Agent: 自适应 maxIterations={} (复杂度={})", adaptive, complexity);
            return adaptive;
        } catch (Exception e) {
            return ac.getMaxIterations();
        }
    }

    /**
     * 根据 queryComplexity 计算实际超时时间。
     */
    private long computeTimeout(RagConfig.AgentConfig ac) {
        try {
            int complexity = RetrievalContext.current().getQueryComplexity();
            // 复杂度 → 超时: 1→10s, 2→15s, 3→20s, 4→30s, 5→30s
            long adaptive = switch (complexity) {
                case 1 -> 10_000;
                case 2 -> 15_000;
                case 3 -> 20_000;
                default -> ac.getTimeoutMs();
            };
            log.debug("Agent: 自适应 timeout={}ms (复杂度={})", adaptive, complexity);
            return adaptive;
        } catch (Exception e) {
            return ac.getTimeoutMs();
        }
    }

    // ==================== 工具去重 ====================

    private static String dedupKey(AssistantMessage.ToolCall tc) {
        return tc.name() + ":" + normalizeArgs(tc.arguments());
    }

    /** 归一化参数（去除空格）作为去重 key */
    private static String normalizeArgs(String args) {
        if (args == null || args.isBlank()) return "";
        return args.replaceAll("\\s+", "").trim();
    }

    // ==================== 多轮检索规划 ====================

    /**
     * 从对话历史中提取上下文注入到 RetrievalContext。
     * <p>支撑 CompressionQueryTransformer 的指代消解。</p>
     */
    private void populateRetrievalContextFromHistory(String conversationId, String currentQuestion) {
        if (conversationId == null || conversationId.isEmpty()) return;

        try {
            List<Message> history = chatMemory.get(conversationId);
            if (history == null || history.isEmpty()) return;

            RetrievalContext ctx = RetrievalContext.current();

            // 提取前序用户查询
            List<String> prevQueries = new ArrayList<>();
            for (Message msg : history) {
                if (msg.getMessageType() == MessageType.USER) {
                    String text = msg.getText();
                    if (text != null && !text.isBlank()) {
                        prevQueries.add(text);
                    }
                }
            }
            if (!prevQueries.isEmpty()) {
                ctx.setPreviousUserQueries(prevQueries);
            }

            // 从前序 assistant 回答中提取景点名称（简单正则）
            List<String> prevSpots = new ArrayList<>();
            String lastEntity = null;
            for (Message msg : history) {
                if (msg.getMessageType() == MessageType.ASSISTANT) {
                    String text = msg.getText();
                    if (text != null) {
                        // 简单提取景点名称（中文名+常见分隔符格式）
                        for (String line : text.split("[\\n。，,]")) {
                            if (line.contains("景点名称") || line.contains("名称")) {
                                String name = extractValue(line);
                                if (name != null && !name.isBlank()) {
                                    prevSpots.add(name);
                                    lastEntity = name;
                                }
                            }
                        }
                    }
                }
            }
            if (!prevSpots.isEmpty()) {
                ctx.setPreviousSpotNames(prevSpots);
            }
            if (lastEntity != null) {
                ctx.setLastDiscussedEntity(lastEntity);
            }

            log.debug("多轮检索规划: prevQueries={}, prevSpots={}, lastEntity={}",
                    prevQueries.size(), prevSpots.size(), lastEntity);
        } catch (Exception e) {
            log.debug("多轮检索规划跳过: {}", e.getMessage());
        }
    }

    /** 从 "景点名称：西湖" 格式提取值 */
    private static String extractValue(String text) {
        int idx = text.indexOf("：");
        if (idx < 0) idx = text.indexOf(":");
        if (idx >= 0 && idx + 1 < text.length()) {
            return text.substring(idx + 1).trim();
        }
        return null;
    }

    // ==================== 消息构建 ====================

    private List<Message> buildInitialMessages(String question, String conversationId,
                                                RagConfig.AgentConfig ac) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(ac)));

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
               "【约束】单次对话工具调用总数不超过 " + ac.getMaxIterations() + " 次。" +
               "同一工具+同一参数最多调用 2 次。多个无依赖的工具可以同时调用。";
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
