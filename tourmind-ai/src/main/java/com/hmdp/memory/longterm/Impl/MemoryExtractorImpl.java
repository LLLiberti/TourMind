package com.hmdp.memory.longterm.Impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.memory.ExtractedMemory;
import com.hmdp.entity.memory.ExtractedMemory.InteractionEvent;
import com.hmdp.entity.memory.ExtractedMemory.MemoryFact;
import com.hmdp.memory.longterm.MemoryExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.*;

/**
 * 记忆提取器 LLM 实现 — 异步从对话中抽取结构化记忆。
 *
 * <h3>设计参考</h3>
 * <ul>
 *   <li>Mem0 ADD-only 策略 — 新事实与旧事实共存，不做 UPDATE/DELETE 分类</li>
 *   <li>后置提取 — Agent 回答后异步运行，失败不影响主流程</li>
 *   <li>超时 5s — 避免 LLM 调用阻塞</li>
 * </ul>
 */
@Slf4j
public class MemoryExtractorImpl implements MemoryExtractor {

    private static final String EXTRACT_PROMPT = """
        分析以下对话，提取用户的偏好、知识和交互事件。

        规则：
        - 只提取对话中明确表达的信息，不要推测
        - 偏好更新：用户表达了对景点类型/预算/区域/出行方式的偏好变化
        - 事实知识：用户提到的事实（去过哪里、喜欢什么、不喜欢什么）
        - 交互事件：本次对话的摘要（问了什么、推荐了什么、结果如何）
        - 如果对话中没有可提取的信息，返回空 JSON

        已有用户画像：
        %s

        对话内容：
        用户问题：%s
        助手回答：%s
        工具调用过程：%s

        输出 JSON（只输出 JSON，不要任何其他文字）：
        {
          "profileUpdates": {
            "preferredSpotTypes": "[\"自然风光\"]",    // null 表示无变化
            "preferredAreas": null,
            "budgetLevel": null,
            "travelStyle": null,
            "preferredTime": null,
            "minRating": null
          },
          "newFacts": [
            {"type": "preference", "content": "用户喜欢安静人少的景点", "importance": 4}
          ],
          "interactionEvent": {
            "summary": "用户询问西湖周边亲子景点，推荐了杭州动物园",
            "entities": ["西湖", "杭州动物园"],
            "outcome": "recommended"
          }
        }
        """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public MemoryExtractorImpl(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public ExtractedMemory extract(Long userId, String question, String answer,
                                    String toolCallsText, String existingProfile) {
        String profileStr = (existingProfile != null && !existingProfile.isBlank())
                ? existingProfile : "（暂无已有画像）";
        String toolStr = (toolCallsText != null && !toolCallsText.isBlank())
                ? toolCallsText : "（无工具调用）";

        String prompt = String.format(EXTRACT_PROMPT, profileStr, question, answer, toolStr);

        try {
            String json = callLlm(prompt);
            if (json == null || json.isBlank()) {
                return empty();
            }
            json = extractJson(json);
            return parseResponse(json);
        } catch (Exception e) {
            log.warn("MemoryExtractor 提取失败: userId={}, msg={}", userId, e.getMessage());
            return empty();
        }
    }

    private String callLlm(String promptText) {
        SystemMessage systemMsg = new SystemMessage("你是一个用户画像分析专家。只输出JSON，不要任何解释。");
        UserMessage userMsg = new UserMessage(promptText);
        Prompt prompt = new Prompt(List.of(systemMsg, userMsg));

        ChatResponse response = chatModel.call(prompt);
        String result = response.getResult().getOutput().getText();
        return result != null ? result.trim() : null;
    }

    @SuppressWarnings("unchecked")
    private ExtractedMemory parseResponse(String json) throws JsonProcessingException {
        Map<String, Object> map = objectMapper.readValue(json, Map.class);

        // 解析 profileUpdates
        Map<String, Object> rawUpdates = (Map<String, Object>) map.get("profileUpdates");
        Map<String, Object> profileUpdates = new LinkedHashMap<>();
        if (rawUpdates != null) {
            for (Map.Entry<String, Object> e : rawUpdates.entrySet()) {
                if (e.getValue() != null) {
                    profileUpdates.put(e.getKey(), e.getValue());
                }
            }
        }

        // 解析 newFacts
        List<Map<String, Object>> rawFacts = (List<Map<String, Object>>) map.get("newFacts");
        List<MemoryFact> facts = new ArrayList<>();
        if (rawFacts != null) {
            for (Map<String, Object> f : rawFacts) {
                facts.add(MemoryFact.builder()
                        .type(getString(f, "type", "fact"))
                        .content(getString(f, "content", ""))
                        .importance(getInt(f, "importance", 1))
                        .build());
            }
        }

        // 解析 interactionEvent
        Map<String, Object> rawEvent = (Map<String, Object>) map.get("interactionEvent");
        InteractionEvent event = null;
        if (rawEvent != null) {
            String summary = getString(rawEvent, "summary", null);
            if (summary != null && !summary.isBlank()) {
                List<String> entities = (List<String>) rawEvent.getOrDefault("entities",
                        Collections.emptyList());
                event = InteractionEvent.builder()
                        .summary(summary)
                        .entities(entities)
                        .outcome(getString(rawEvent, "outcome", "answered"))
                        .build();
            }
        }

        return ExtractedMemory.builder()
                .profileUpdates(profileUpdates)
                .newFacts(facts)
                .interactionEvent(event)
                .build();
    }

    private static ExtractedMemory empty() {
        return ExtractedMemory.builder().build();
    }

    private static String extractJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            if (start > 0) {
                int end = trimmed.lastIndexOf("```");
                if (end > start) return trimmed.substring(start, end).trim();
            }
        }
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }
        return trimmed;
    }

    private static String getString(Map<String, Object> map, String key, String def) {
        Object val = map.get(key);
        return val != null ? val.toString() : def;
    }

    private static int getInt(Map<String, Object> map, String key, int def) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return def;
    }
}
