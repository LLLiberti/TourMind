package com.hmdp.agent;

import com.hmdp.config.RagConfig;
import com.hmdp.rag.retrieval.HybridDocumentRetriever;
import com.hmdp.service.ISpotToolService;
import com.hmdp.service.IWeatherService;
import com.hmdp.tool.SearchKnowledgeBaseTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ReActAgentLoop 单元测试 — 验证手动 ReACT 循环逻辑。
 *
 * <p>通过 ReflectionTestUtils 注入 Mock 的 ChatClient 链，
 * 避免依赖真实 LLM 调用。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReActAgentLoop 单元测试")
class ReActAgentLoopTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private ChatClient agentClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponse;

    @Mock
    private ChatMemory chatMemory;

    @Mock
    private SearchKnowledgeBaseTool searchTool;

    @Mock
    private ISpotToolService spotToolService;

    @Mock
    private IWeatherService weatherService;

    @Mock
    private HybridDocumentRetriever retriever;

    @Mock
    private RagConfig ragConfig;

    @Mock
    private RagConfig.AgentConfig agentConfig;

    @Mock
    private RagConfig.PlannerConfig plannerConfig;

    @Mock
    private PlannerAgent planner;

    private ReActAgentLoop loop;

    @BeforeEach
    void setUp() {
        loop = new ReActAgentLoop(chatModel);

        // 注入 Mock ChatClient 替代构造函数中创建的 agentClient
        ReflectionTestUtils.setField(loop, "agentClient", agentClient);
        ReflectionTestUtils.setField(loop, "chatMemory", chatMemory);
        ReflectionTestUtils.setField(loop, "searchTool", searchTool);
        ReflectionTestUtils.setField(loop, "spotToolService", spotToolService);
        ReflectionTestUtils.setField(loop, "weatherService", weatherService);
        ReflectionTestUtils.setField(loop, "retriever", retriever);
        ReflectionTestUtils.setField(loop, "ragConfig", ragConfig);
        ReflectionTestUtils.setField(loop, "planner", planner);

        // 默认配置
        when(ragConfig.getAgent()).thenReturn(agentConfig);
        when(agentConfig.getMaxIterations()).thenReturn(10);
        when(agentConfig.getTimeoutMs()).thenReturn(30_000L);
        when(agentConfig.getSystemPrompt()).thenReturn("你是旅游助手。");
        when(agentConfig.getPlanner()).thenReturn(plannerConfig);
        when(plannerConfig.isEnabled()).thenReturn(true);
        when(plannerConfig.getMaxSubIterations()).thenReturn(3);
        when(plannerConfig.getSubTimeoutMs()).thenReturn(15_000L);

        // 空历史
        when(chatMemory.get(anyString())).thenReturn(Collections.emptyList());

        // ChatClient 链
        when(agentClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponse);
    }

    @Test
    @DisplayName("单轮文本响应 — LLM 直接回答，无工具调用")
    void shouldReturnAnswerDirectlyWithoutToolCalls() {
        AssistantMessage textMsg = new AssistantMessage("西湖是杭州著名的景点。");
        ChatResponse response = new ChatResponse(List.of(new Generation(textMsg)));
        when(callResponse.chatResponse()).thenReturn(response);

        AgentResult result = loop.thinkAndAct("介绍一下西湖", "conv1", null, null);

        assertThat(result.answer()).isEqualTo("西湖是杭州著名的景点。");
        assertThat(result.trace().getSteps()).isEmpty();
    }

    @Test
    @DisplayName("带工具调用 — Agent 搜索知识库后回答")
    void shouldCallToolAndThenAnswer() {
        // Iter 1: tool call → searchKnowledgeBase
        AssistantMessage toolMsg = new AssistantMessage("",
                Map.of(), List.of(new AssistantMessage.ToolCall(
                        "call_1", "function", "searchKnowledgeBase", "{\"query\":\"雷峰塔\"}")));
        ChatResponse toolResponse = new ChatResponse(List.of(new Generation(toolMsg)));

        // Iter 2: text answer
        AssistantMessage textMsg = new AssistantMessage("雷峰塔门票价格...");
        ChatResponse textResponse = new ChatResponse(List.of(new Generation(textMsg)));

        when(callResponse.chatResponse())
                .thenReturn(toolResponse)  // iter 1 — tool call
                .thenReturn(textResponse); // iter 2 — answer

        when(searchTool.execute(anyString()))
                .thenReturn("[检索结果] 1 篇文档 | CONFIDENT | 雷峰塔...");

        AgentResult result = loop.thinkAndAct("雷峰塔门票", "conv2", null, null);

        assertThat(result.answer()).isEqualTo("雷峰塔门票价格...");
        assertThat(result.trace().getSteps()).hasSize(2); // TOOL_CALL + OBSERVATION
        assertThat(result.trace().getSteps().get(0).getToolName()).isEqualTo("searchKnowledgeBase");
    }

    @Test
    @DisplayName("最大迭代 — 超过限制后终止并返回超时消息")
    void shouldTerminateOnMaxIterations() {
        when(agentConfig.getMaxIterations()).thenReturn(2);

        // 始终返回 tool calls，触发无限循环测试
        AssistantMessage toolMsg = new AssistantMessage("",
                Map.of(), List.of(new AssistantMessage.ToolCall(
                        "c1", "function", "searchKnowledgeBase", "{\"query\":\"x\"}")));
        ChatResponse toolResponse = new ChatResponse(List.of(new Generation(toolMsg)));
        when(callResponse.chatResponse()).thenReturn(toolResponse);
        when(searchTool.execute(anyString())).thenReturn("no results");

        AgentResult result = loop.thinkAndAct("测试", "conv3", null, null);

        assertThat(result.answer()).contains("超时");
        assertThat(result.trace().isHitMaxIterations()).isTrue();
    }

    // ==================== thinkAndActWithPlan ====================

    @Test
    @DisplayName("简单 query → Planner 返回 isComplex=false，透传到 thinkAndAct")
    void shouldPassthroughSimpleQuery() {
        AgentPlan simplePlan = new AgentPlan();
        simplePlan.setComplex(false);
        when(planner.decompose(anyString())).thenReturn(simplePlan);

        // 准备 thinkAndAct 的响应
        AssistantMessage textMsg = new AssistantMessage("西湖是杭州著名景点。");
        ChatResponse response = new ChatResponse(List.of(new Generation(textMsg)));
        when(callResponse.chatResponse()).thenReturn(response);

        AgentResult result = loop.thinkAndActWithPlan("西湖", "conv4", null, null);

        assertThat(result.answer()).isEqualTo("西湖是杭州著名景点。");
    }

    @Test
    @DisplayName("复杂 query → Planner 分解+综合")
    void shouldDecomposeAndSynthesize() {
        AgentPlan plan = new AgentPlan();
        plan.setComplex(true);
        plan.setSubTasks(List.of(
                AgentPlan.SubTask.builder().id("s1").question("西湖特色")
                        .dependsOn(java.util.Collections.emptyList()).build(),
                AgentPlan.SubTask.builder().id("s2").question("雷峰塔特色")
                        .dependsOn(java.util.Collections.emptyList()).build()));
        when(planner.decompose(anyString())).thenReturn(plan);
        when(planner.synthesize(anyString(), anyList(), anyList()))
                .thenReturn("西湖和雷峰塔对比：西湖免费，雷峰塔40元。");

        // 子任务的 thinkAndAct 响应
        AssistantMessage textMsg = new AssistantMessage("子任务回答");
        ChatResponse subResponse = new ChatResponse(List.of(new Generation(textMsg)));
        when(callResponse.chatResponse()).thenReturn(subResponse);

        AgentResult result = loop.thinkAndActWithPlan(
                "西湖和雷峰塔对比", "conv5", null, null);

        assertThat(result.answer()).contains("西湖");
        assertThat(result.answer()).contains("雷峰塔");
    }
}
