package com.hmdp.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.RagConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Planner Agent — 将复杂用户问题分解为可并行的子任务。
 *
 * <h3>职责</h3>
 * <ol>
 *   <li><b>decompose</b>：判断 query 是否为复合问题，若是则拆分为独立子任务+依赖关系</li>
 *   <li><b>synthesize</b>：综合所有子任务结果生成最终连贯回答</li>
 * </ol>
 *
 * <h3>设计</h3>
 * <p>decompose 使用 LLM 调用（一次），输出结构化 JSON。
 * 简单 query 返回 {@code isComplex=false}，调用方跳过后续分解逻辑。
 * JSON 解析失败时 fallback 到简单模式。</p>
 */
@Slf4j
@Service
public class PlannerAgent {

    private static final String DECOMPOSE_PROMPT = """
        判断用户问题是否需要拆分为多个独立子问题。如果需要，输出子问题列表。

        规则：
        - 简单问题（单一意图）→ isComplex: false, subTasks: []
        - 复合问题（多意图）→ isComplex: true，拆分子问题
        - 每个子问题必须自包含，包含足够上下文独立回答
        - 标记依赖关系：子问题B需要子问题A的结果时标记 dependsOn
        - 最多拆分 %d 个子问题

        示例输入："西湖和雷峰塔的特色是什么？门票各多少？"
        输出：
        {"isComplex":true,"subTasks":[
          {"id":"s1","question":"西湖的特色介绍和评分","dependsOn":[]},
          {"id":"s2","question":"雷峰塔的特色介绍和评分","dependsOn":[]},
          {"id":"s3","question":"西湖的门票价格是多少","dependsOn":["s1"]},
          {"id":"s4","question":"雷峰塔的门票价格是多少","dependsOn":["s2"]}
        ]}

        只输出 JSON，不要任何其他文字。

        用户问题：%s
        """;

    private static final String SYNTHESIZE_PROMPT = """
        综合以下子问题的回答，生成一个连贯、完整的最终回答。

        原始用户问题：%s

        各子问题回答：
        %s

        规则：
        - 合并所有子回答为一个连贯的段落
        - 去除重复信息
        - 保持简洁、有条理
        - 如果需要对比或推荐，给出清晰结论
        """;

    private final ChatClient client;
    private final ObjectMapper objectMapper;
    private final int maxSubTasks;

    public PlannerAgent(@Qualifier("deepSeekChatModel") ChatModel chatModel, RagConfig ragConfig) {
        this.client = ChatClient.create(chatModel);
        this.objectMapper = new ObjectMapper();
        this.maxSubTasks = ragConfig.getAgent().getPlanner().getMaxSubTasks();
    }

    /**
     * 分解用户问题为子任务。
     *
     * @param question 用户原始问题
     * @return AgentPlan（isComplex=false 时 subTasks 为空，调用方直接走原逻辑）
     */
    public AgentPlan decompose(String question) {
        String prompt = String.format(DECOMPOSE_PROMPT, maxSubTasks, question);

        String json;
        try {
            json = client.prompt().user(prompt).call().content();
            if (json == null || json.isBlank()) {
                return simplePlan();
            }
            json = extractJson(json);
        } catch (Exception e) {
            log.warn("Planner decompose LLM 调用失败: {}", e.getMessage());
            return simplePlan();
        }

        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            Boolean isComplex = (Boolean) map.get("isComplex");
            if (isComplex == null || !isComplex) {
                return simplePlan();
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawTasks = (List<Map<String, Object>>) map.get("subTasks");
            if (rawTasks == null || rawTasks.isEmpty()) {
                return simplePlan();
            }

            List<AgentPlan.SubTask> subTasks = new ArrayList<>();
            for (int i = 0; i < Math.min(rawTasks.size(), maxSubTasks); i++) {
                Map<String, Object> rt = rawTasks.get(i);
                String id = (String) rt.getOrDefault("id", "s" + (i + 1));
                String q = (String) rt.get("question");
                if (q == null || q.isBlank()) continue;

                @SuppressWarnings("unchecked")
                List<String> deps = (List<String>) rt.get("dependsOn");
                subTasks.add(AgentPlan.SubTask.builder()
                        .id(id)
                        .question(q)
                        .dependsOn(deps != null ? deps : Collections.emptyList())
                        .build());
            }

            if (subTasks.isEmpty()) {
                return simplePlan();
            }

            AgentPlan plan = new AgentPlan();
            plan.setComplex(true);
            plan.setSubTasks(subTasks);
            log.info("Planner 分解完成: {} 个子任务", subTasks.size());
            return plan;

        } catch (JsonProcessingException | ClassCastException e) {
            log.warn("Planner JSON 解析失败，回退简单模式: {}", e.getMessage());
            return simplePlan();
        }
    }

    /**
     * 综合所有子任务结果 → 最终回答。
     */
    public String synthesize(String originalQuestion, List<AgentPlan.SubTask> subTasks,
                             List<String> subResults) {
        StringBuilder resultsBlock = new StringBuilder();
        for (int i = 0; i < subTasks.size(); i++) {
            resultsBlock.append("子问题").append(i + 1).append("：")
                        .append(subTasks.get(i).getQuestion()).append("\n")
                        .append("回答：").append(subResults.get(i)).append("\n\n");
        }

        String prompt = String.format(SYNTHESIZE_PROMPT, originalQuestion, resultsBlock);

        try {
            String answer = client.prompt().user(prompt).call().content();
            return answer != null ? answer : fallbackMerge(subResults);
        } catch (Exception e) {
            log.warn("Planner synthesize LLM 调用失败: {}", e.getMessage());
            return fallbackMerge(subResults);
        }
    }

    // ==================== 内部方法 ====================

    private AgentPlan simplePlan() {
        AgentPlan plan = new AgentPlan();
        plan.setComplex(false);
        return plan;
    }

    /** 从 LLM 输出中提取 JSON（去除可能的 markdown 包裹） */
    private static String extractJson(String raw) {
        String trimmed = raw.trim();
        // 移除 ```json ... ``` 包裹
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            if (start > 0) {
                int end = trimmed.lastIndexOf("```");
                if (end > start) {
                    return trimmed.substring(start, end).trim();
                }
            }
        }
        // 找到 { 开头 } 结尾
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }
        return trimmed;
    }

    /** LLM 综合失败时的简单合并 */
    private String fallbackMerge(List<String> subResults) {
        return subResults.stream()
                .filter(r -> r != null && !r.isBlank())
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
