package com.hmdp.memory.longterm.Impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.memory.ExtractedMemory;
import com.hmdp.entity.memory.ExtractedMemory.ConversationSummaryResult;
import com.hmdp.entity.memory.ExtractedMemory.MemoryFact;
import com.hmdp.entity.memory.TurnRecord;
import com.hmdp.memory.longterm.MemoryExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.*;

/**
 * 记忆提取器 LLM 实现 — 批量模式（v3.1 会话级 upsert）。
 *
 * <h3>v3.1 变更</h3>
 * <ul>
 *   <li>conversationSummary 改为会话级字段：topic / summary / userGoal / discussedEntities
 *       / currentFocus / unresolvedQuestions</li>
 *   <li>支持增量更新：传入已有摘要，LLM 在此基础上合并新信息</li>
 *   <li>importance 评分标准强化：1-2 级琐碎信息不应提取</li>
 * </ul>
 */
@Slf4j
public class MemoryExtractorImpl implements MemoryExtractor {

    private static final String BATCH_EXTRACT_PROMPT = """
        分析以下多轮对话，提取/更新对话摘要、用户新偏好和新知识。

        规则：
        - 只提取对话中明确表达的、跨越单轮的信息模式
        - 偏好更新：用户多次表达或明确声明的偏好变化
          （null 表示该字段无变化，不要重复已有画像中已有的信息）
        - 事实知识：跨轮浮现的用户特征（偏好主题、预算敏感度、出行习惯等）
        - importance 评分标准：
          5=核心身份信息（如家庭结构、职业相关出行需求）
          4=明确强偏好（如反复出现的景点类型倾向）
          3=值得记录的偏好/知识（如单次提到但有意义的倾向）
          1-2=琐碎信息（不应提取，除非在多轮中反复出现）
        - 对话摘要字段：
          * topic: 一句话概括整个对话的核心主题
          * summary: 累积摘要，覆盖关键话题链、决策、结果。若已有摘要则在此基础上合并更新
          * userGoal: 用户的核心目标/意图
          * discussedEntities: 对话中涉及的景点、地名列表
          * currentFocus: 用户当前最关注的问题或方向
          * unresolvedQuestions: 用户提出但尚未完全解决的问题
        - 如果对话中没有实质性新信息，返回空字段

        已有用户画像：
        %s

        已有会话摘要（增量更新基础）：
        %s

        对话历史（%d 轮）：
        %s

        输出 JSON（只输出 JSON，不要任何其他文字）：
        {
          "conversationSummary": {
            "topic": "杭州亲子游景点推荐与门票对比",
            "summary": "用户从询问西湖开始，逐步聚焦到亲子景点和门票价格，最终选择了杭州动物园作为亲子选项，并确认了门票¥60。用户对性价比敏感。",
            "userGoal": "寻找适合带孩子游玩的杭州景点，预算中等偏下",
            "discussedEntities": ["西湖", "雷峰塔", "杭州动物园"],
            "currentFocus": "杭州动物园门票和优惠券",
            "unresolvedQuestions": ["是否需要提前购票"]
          },
          "profileUpdates": {
            "preferredSpotTypes": null,
            "preferredAreas": null,
            "budgetLevel": null,
            "travelStyle": null,
            "preferredTime": null,
            "minRating": null
          },
          "newFacts": [
            {"type": "preference", "content": "用户偏好亲子类景点，预算中等偏下，重视性价比", "importance": 4}
          ]
        }
        """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public MemoryExtractorImpl(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public ExtractedMemory extractBatch(Long userId, List<TurnRecord> turns,
                                         String existingProfile) {
        return extractBatch(userId, turns, existingProfile, "");
    }

    /**
     * 批量提取（带已有摘要的增量模式）。
     *
     * @param existingSummary 已有会话摘要文本（用于增量合并，首次为空）
     */
    public ExtractedMemory extractBatch(Long userId, List<TurnRecord> turns,
                                         String existingProfile, String existingSummary) {
        if (turns == null || turns.isEmpty()) {
            return empty();
        }

        String profileStr = (existingProfile != null && !existingProfile.isBlank())
                ? existingProfile : "（暂无已有画像）";
        String summaryStr = (existingSummary != null && !existingSummary.isBlank())
                ? existingSummary : "（暂无已有摘要，这是首次提取）";
        String turnsText = buildTurnsText(turns);

        String prompt = String.format(BATCH_EXTRACT_PROMPT, profileStr, summaryStr,
                turns.size(), turnsText);

        try {
            String json = callLlm(prompt);
            if (json == null || json.isBlank()) {
                return empty();
            }
            json = extractJson(json);
            return parseResponse(json);
        } catch (Exception e) {
            log.warn("MemoryExtractor 批量提取失败: userId={}, turns={}, msg={}",
                    userId, turns.size(), e.getMessage());
            return empty();
        }
    }

    /**
     * 将多轮对话格式化为 prompt 中的对话历史文本。
     */
    private String buildTurnsText(List<TurnRecord> turns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < turns.size(); i++) {
            TurnRecord t = turns.get(i);
            sb.append("[轮").append(i + 1).append("]\n");
            sb.append("用户：").append(truncate(t.question(), 200)).append("\n");
            sb.append("助手：").append(truncate(t.answer(), 300)).append("\n");
            if (!t.toolCallsText().isBlank()) {
                sb.append("工具调用：").append(truncate(t.toolCallsText(), 200)).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String callLlm(String promptText) {
        SystemMessage systemMsg = new SystemMessage("你是一个用户画像和对话摘要分析专家。只输出JSON，不要任何解释。");
        UserMessage userMsg = new UserMessage(promptText);
        Prompt prompt = new Prompt(List.of(systemMsg, userMsg));

        ChatResponse response = chatModel.call(prompt);
        String result = response.getResult().getOutput().getText();
        return result != null ? result.trim() : null;
    }

    @SuppressWarnings("unchecked")
    private ExtractedMemory parseResponse(String json) throws JsonProcessingException {
        Map<String, Object> map = objectMapper.readValue(json, Map.class);

        // 解析 conversationSummary（v3.1 会话级字段）
        Map<String, Object> rawSummary = (Map<String, Object>) map.get("conversationSummary");
        ConversationSummaryResult summary = null;
        if (rawSummary != null) {
            String summaryText = getString(rawSummary, "summary", null);
            if (summaryText != null && !summaryText.isBlank()) {
                summary = ConversationSummaryResult.builder()
                        .topic(getString(rawSummary, "topic", null))
                        .summary(summaryText)
                        .userGoal(getString(rawSummary, "userGoal", null))
                        .discussedEntities(getStringList(rawSummary, "discussedEntities"))
                        .currentFocus(getString(rawSummary, "currentFocus", null))
                        .unresolvedQuestions(getStringList(rawSummary, "unresolvedQuestions"))
                        .build();
            }
        }

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
                int importance = getInt(f, "importance", 1);
                facts.add(MemoryFact.builder()
                        .type(getString(f, "type", "fact"))
                        .content(getString(f, "content", ""))
                        .importance(importance)
                        .build());
            }
        }

        return ExtractedMemory.builder()
                .conversationSummary(summary)
                .profileUpdates(profileUpdates)
                .newFacts(facts)
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

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
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

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) val) {
                if (item != null) result.add(item.toString());
            }
            return result;
        }
        return Collections.emptyList();
    }
}
