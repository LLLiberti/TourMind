package com.hmdp.rag.generation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成质量守护 — Citation 注入 + 答案验证 + 去幻觉 + 冲突检测。
 *
 * <h3>调用时机</h3>
 * <ol>
 *   <li><b>生成前</b> — 为检索文档注入引用 ID，拼接到 prompt</li>
 *   <li><b>生成后</b> — 事实性校验：提取断言 → 与源文档对齐 → 标记无源断言</li>
 *   <li><b>冲突检测</b> — 比较知识库静态信息与实时工具返回值的差异</li>
 * </ol>
 *
 * <h3>降级策略</h3>
 * <p>所有校验为<b>非阻塞</b>：校验失败时标记警告但不拦截回答。
 * 校验 LLM 调用失败时静默跳过。</p>
 */
@Slf4j
public class GenerationGuard {

    private static final String FACT_CHECK_PROMPT = """
        你是事实性校验专家。检查以下回答中的具体事实断言是否可以在提供的文档中找到依据。

        规则：
        - 逐条列出回答中的所有具体事实断言（数字、名称、价格、评分、时间、地址等）
        - 在每个断言后标注是否有文档依据：[有源] 或 [无源]
        - 只输出检查结果，不要解释

        参考文档：
        %s

        待检查回答：
        %s

        校验结果：""";

    private static final Pattern NUMBER_PATTERN = Pattern.compile(
            "\\d+\\.?\\d*\\s*(?:元|分|公里|米|小时|分钟|点|岁|张|个)");

    private final ChatModel chatModel;

    public GenerationGuard(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    // ==================== Citation 注入 ====================

    /**
     * 为检索文档注入引用 ID，并生成 Citation 指令 prompt。
     *
     * @param documents 检索到的文档列表
     * @return 含引用标记的文档文本 + citation 指令
     */
    public String buildCitationContext(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【引用说明】以下文档信息带有引用编号 [N]，回答时请在引用该信息处标注编号。\n\n");

        for (int i = 0; i < Math.min(documents.size(), 10); i++) {
            Document doc = documents.get(i);
            String spotId = (String) doc.getMetadata().get("spotId");
            String spotName = (String) doc.getMetadata().get("spotName");
            String source = spotName != null ? spotName : (spotId != null ? "景点" + spotId : "未知");

            sb.append("--- 文档 [").append(i + 1).append("] 来源：").append(source).append(" ---\n");
            sb.append(doc.getText()).append("\n\n");
        }

        sb.append("\n【重要】回答中引用上述信息时，请在事实后标注引用编号，例如：\"评分 4.5 分 [1]\"。\n");
        return sb.toString();
    }

    /**
     * 从最终回答中提取引用统计（回答中出现了哪些引用编号）。
     */
    public static List<Integer> extractCitations(String answer) {
        if (answer == null || answer.isBlank()) return Collections.emptyList();
        List<Integer> refs = new ArrayList<>();
        Matcher m = Pattern.compile("\\[(\\d+)\\]").matcher(answer);
        while (m.find()) {
            try { refs.add(Integer.parseInt(m.group(1))); }
            catch (NumberFormatException ignored) {}
        }
        return refs;
    }

    // ==================== 事实性校验 ====================

    /**
     * 校验结果。
     */
    public static class FactCheckResult {
        private final boolean passed;
        private final List<String> unsourcedClaims;
        private final String detail;

        public FactCheckResult(boolean passed, List<String> unsourcedClaims, String detail) {
            this.passed = passed;
            this.unsourcedClaims = unsourcedClaims != null ? unsourcedClaims : Collections.emptyList();
            this.detail = detail;
        }

        public boolean isPassed() { return passed; }
        public List<String> getUnsourcedClaims() { return unsourcedClaims; }
        public String getDetail() { return detail; }
    }

    /**
     * 对回答进行事实性校验。
     * <p>使用 LLM 提取断言并与参考文档对齐。
     * 如果 LLM 不可用，降级为简单数字匹配。</p>
     *
     * @param answer          Agent 生成的回答
     * @param sourceDocuments 检索源文档（用于对齐）
     * @return 校验结果
     */
    public FactCheckResult checkFactuality(String answer, List<Document> sourceDocuments) {
        if (answer == null || answer.isBlank()) {
            return new FactCheckResult(true, Collections.emptyList(), "空回答");
        }

        // 构建参考文档文本
        StringBuilder docText = new StringBuilder();
        if (sourceDocuments != null) {
            for (Document doc : sourceDocuments) {
                docText.append(doc.getText()).append("\n");
            }
        }

        if (docText.isEmpty()) {
            // 无参考文档，跳过深度校验，仅做简单数字检测
            return simpleNumberCheck(answer);
        }

        try {
            String promptText = String.format(FACT_CHECK_PROMPT, docText, answer);
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage("你是一个事实性校验专家。"),
                    new UserMessage(promptText)));
            ChatResponse response = chatModel.call(prompt);
            String result = response.getResult().getOutput().getText();

            // 统计 [无源] 数量
            int unsourcedCount = 0;
            if (result != null) {
                unsourcedCount = result.split("\\[无源\\]").length - 1;
            }

            boolean passed = unsourcedCount <= 1; // 允许最多 1 个无源断言（可能是常识）
            return new FactCheckResult(passed,
                    Collections.emptyList(),
                    result != null ? result : "校验失败");

        } catch (Exception e) {
            log.warn("事实性校验 LLM 调用失败，降级简单检测: {}", e.getMessage());
            return simpleNumberCheck(answer);
        }
    }

    /**
     * 简单数字匹配校验（LLM 不可用时的降级方案）。
     */
    private FactCheckResult simpleNumberCheck(String answer) {
        Matcher m = NUMBER_PATTERN.matcher(answer);
        List<String> numbers = new ArrayList<>();
        while (m.find()) {
            numbers.add(m.group());
        }
        return new FactCheckResult(true, Collections.emptyList(),
                "简单数字检测：" + numbers + "（未与源文档对齐）");
    }

    // ==================== 冲突检测 ====================

    /**
     * 冲突检测结果。
     */
    public static class ConflictInfo {
        private final String field;
        private final String kbValue;
        private final String realtimeValue;
        private final String suggestion;

        public ConflictInfo(String field, String kbValue, String realtimeValue, String suggestion) {
            this.field = field;
            this.kbValue = kbValue;
            this.realtimeValue = realtimeValue;
            this.suggestion = suggestion;
        }

        public String getField() { return field; }
        public String getKbValue() { return kbValue; }
        public String getRealtimeValue() { return realtimeValue; }
        public String getSuggestion() { return suggestion; }
    }

    /**
     * 检测知识库静态信息与实时工具返回值的冲突。
     *
     * @param kbFields     知识库中的字段值（如 ticketPrice → "50"）
     * @param realtimeFields 实时工具的返回值（如 getSpotPrice → "55"）
     * @return 冲突列表
     */
    public List<ConflictInfo> detectConflicts(Map<String, String> kbFields,
                                               Map<String, String> realtimeFields) {
        if (kbFields == null || realtimeFields == null) return Collections.emptyList();

        List<ConflictInfo> conflicts = new ArrayList<>();
        for (Map.Entry<String, String> entry : kbFields.entrySet()) {
            String field = entry.getKey();
            String kbVal = entry.getValue();
            String rtVal = realtimeFields.get(field);

            if (rtVal != null && !rtVal.equals(kbVal)) {
                conflicts.add(new ConflictInfo(field, kbVal, rtVal,
                        field + "：知识库记录为 " + kbVal + "，实时数据为 " + rtVal + "，以实时数据为准。"));
            }
        }
        return conflicts;
    }

    /**
     * 生成冲突提示文本，注入到回答末尾。
     */
    public static String buildConflictNote(List<ConflictInfo> conflicts) {
        if (conflicts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n⚠️ 【信息差异提示】\n");
        for (ConflictInfo c : conflicts) {
            sb.append("- ").append(c.getSuggestion()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 生成无源断言警告文本。
     */
    public static String buildUnsourceWarning(FactCheckResult checkResult) {
        if (checkResult.isPassed()) return "";
        return "\n\n⚠️ 以上部分信息未在知识库中找到直接依据，建议以景区官方信息为准。";
    }
}
