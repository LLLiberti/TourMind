package com.hmdp.agent;

import com.hmdp.config.RagConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * PlannerAgent 单元测试 — 验证 query 分解和结果综合。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlannerAgent 单元测试")
class PlannerAgentTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private ChatClient mockClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponse;

    @Mock
    private RagConfig ragConfig;

    @Mock
    private RagConfig.AgentConfig agentConfig;

    @Mock
    private RagConfig.PlannerConfig plannerConfig;

    private PlannerAgent planner;

    @BeforeEach
    void setUp() {
        when(ragConfig.getAgent()).thenReturn(agentConfig);
        when(agentConfig.getPlanner()).thenReturn(plannerConfig);
        when(plannerConfig.getMaxSubTasks()).thenReturn(5);

        planner = new PlannerAgent(chatModel, ragConfig);
        // 注入 Mock ChatClient
        ReflectionTestUtils.setField(planner, "client", mockClient);

        when(mockClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponse);
    }

    @Test
    @DisplayName("简单 query → isComplex=false，调用方直接走原逻辑")
    void shouldReturnSimplePlanForSingleIntent() {
        when(callResponse.content()).thenReturn("{\"isComplex\":false,\"subTasks\":[]}");

        AgentPlan plan = planner.decompose("西湖门票多少钱");

        assertThat(plan.isComplex()).isFalse();
        assertThat(plan.getSubTasks()).isEmpty();
    }

    @Test
    @DisplayName("复合 query → 分解为子任务+依赖关系")
    void shouldDecomposeCompoundQuery() {
        when(callResponse.content()).thenReturn("""
                {"isComplex":true,"subTasks":[
                  {"id":"s1","question":"西湖的特色介绍","dependsOn":[]},
                  {"id":"s2","question":"雷峰塔的特色介绍","dependsOn":[]},
                  {"id":"s3","question":"西湖门票价格","dependsOn":["s1"]}
                ]}""");

        AgentPlan plan = planner.decompose("西湖和雷峰塔的特色和门票");

        assertThat(plan.isComplex()).isTrue();
        assertThat(plan.getSubTasks()).hasSize(3);
        assertThat(plan.getSubTasks().get(0).getDependsOn()).isEmpty();
        assertThat(plan.getSubTasks().get(2).getDependsOn()).containsExactly("s1");
    }

    @Test
    @DisplayName("LLM 返回无效 JSON → fallback 简单模式")
    void shouldFallbackOnInvalidJson() {
        when(callResponse.content()).thenReturn("这不是有效的 JSON 响应");

        AgentPlan plan = planner.decompose("复杂问题");

        assertThat(plan.isComplex()).isFalse();
    }

    @Test
    @DisplayName("综合 — 合并多个子结果生成最终回答")
    void shouldSynthesizeSubResults() {
        when(callResponse.content()).thenReturn("为您对比分析：西湖免费开放，雷峰塔门票40元...");

        List<AgentPlan.SubTask> tasks = List.of(
                subTask("s1", "西湖特色", List.of()),
                subTask("s2", "雷峰塔特色", List.of()));
        List<String> results = List.of("西湖是自然风景区", "雷峰塔是文化古迹");

        String answer = planner.synthesize("西湖和雷峰塔对比", tasks, results);

        assertThat(answer).contains("西湖");
        assertThat(answer).contains("雷峰塔");
    }

    @Test
    @DisplayName("综合 LLM 失败 → fallback 简单拼接")
    void shouldFallbackMergeOnSynthesizeError() {
        when(callResponse.content()).thenThrow(new RuntimeException("LLM 不可用"));

        List<AgentPlan.SubTask> tasks = List.of(subTask("s1", "q1", List.of()));
        List<String> results = List.of("结果1");

        String answer = planner.synthesize("问题", tasks, results);

        assertThat(answer).contains("结果1");
    }

    @Test
    @DisplayName("markdown 包裹的 JSON → 正确提取")
    void shouldExtractJsonFromMarkdown() {
        when(callResponse.content()).thenReturn("""
                ```json
                {"isComplex":true,"subTasks":[{"id":"s1","question":"查询天气","dependsOn":[]}]}
                ```
                以上是分解结果。""");

        AgentPlan plan = planner.decompose("今天天气怎么样");

        assertThat(plan.isComplex()).isTrue();
        assertThat(plan.getSubTasks()).hasSize(1);
    }

    private static AgentPlan.SubTask subTask(String id, String question, List<String> deps) {
        return AgentPlan.SubTask.builder().id(id).question(question).dependsOn(deps).build();
    }
}
