package com.hmdp.rag.query;

import com.hmdp.rag.RetrievalContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import java.util.List;

/**
 * 多轮对话指代消解器 — 将用户查询中的代词/序数词/隐式引用替换为具体实体。
 *
 * <h3>为什么需要</h3>
 * <p>多轮对话中用户常使用代词和简短指代：
 * <ul>
 *   <li>"第二个景点呢？" → 需解析上一轮检索结果中第二个景点的名称</li>
 *   <li>"它的门票多少钱？" → 需解析"它"指代的上一个实体</li>
 *   <li>"附近还有什么？" → 需解析"附近"指代的上一个景点的区域/坐标</li>
 * </ul>
 * 不解消这些指代，retriever 收到"第二个"/"它"/"附近"只能返回低质量结果。</p>
 *
 * <h3>在管线中的位置</h3>
 * <pre>
 * CompressionQueryTransformer（指代消解）→ RewriteQueryTransformer（关键词改写）→ HybridDocumentRetriever
 * </pre>
 *
 * <h3>降级策略</h3>
 * <p>LLM 调用失败或 conversationContext 为空时直接透传原始 query，
 * 不阻塞检索管线。</p>
 */
@Slf4j
public class CompressionQueryTransformer implements QueryTransformer {

    private static final String COMPRESSION_PROMPT = """
        你是多轮对话查询消解专家。根据对话历史和查询上下文，将用户的指代词替换为具体的实体名称。

        规则：
        - "第二个"、"第一个"、"最后那个"等序数指代 → 替换为对应实体的具体名称
        - "它"、"他"、"她"、"这个"、"那个"等代词 → 替换为被指代实体的具体名称
        - "附近"、"周边"、"旁边"等地点的指代 → 替换为被指代地点的名称 + "附近"
        - "同上"、"一样"等省略 → 补全为完整查询
        - 如果查询本身已经明确不包含指代词，则原样返回
        - 输出仅包含消解后的完整查询文本，不要任何解释或标点包裹

        对话上下文：
        %s

        用户当前查询：%s
        消解后查询：""";

    private static final boolean FALLBACK_ON_ERROR = true;

    private final ChatModel chatModel;

    public CompressionQueryTransformer(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public Query transform(Query query) {
        String originalText = query.text();
        if (originalText == null || originalText.isBlank()) {
            return query;
        }

        RetrievalContext ctx = RetrievalContext.current();
        String conversationCtx = buildConversationContext(ctx);
        if (conversationCtx.isEmpty()) {
            log.debug("Compression: 无对话上下文，跳过指代消解");
            return query;
        }

        // 快速判断：如果查询中不含任何指代词特征，跳过 LLM 调用
        if (!needsResolution(originalText)) {
            log.debug("Compression: 查询无指代词特征，跳过: {}", originalText);
            return query;
        }

        log.info("Compression: 原始 query = {}", originalText);

        try {
            String resolved = resolveReferences(originalText, conversationCtx);
            if (resolved == null || resolved.isBlank() || resolved.equals(originalText)) {
                log.debug("Compression: 消解无变化，保持原 query");
                return query;
            }

            log.info("Compression: 消解后 query = {}", resolved);
            return query.mutate().text(resolved).build();

        } catch (Exception e) {
            if (FALLBACK_ON_ERROR) {
                log.warn("Compression: 指代消解失败，回退原始 query: {}", e.getMessage());
                return query;
            }
            throw new RuntimeException("Query compression failed", e);
        }
    }

    /**
     * 从 RetrievalContext 构建对话上下文文本。
     */
    private String buildConversationContext(RetrievalContext ctx) {
        if (ctx == null) return "";

        StringBuilder sb = new StringBuilder();

        // 前序用户查询
        List<String> prevQueries = ctx.getPreviousUserQueries();
        if (prevQueries != null && !prevQueries.isEmpty()) {
            sb.append("前序用户查询：\n");
            for (int i = 0; i < prevQueries.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(prevQueries.get(i)).append("\n");
            }
        }

        // 前序检索结果中的景点信息（用于解析"第二个景点"等指代）
        List<String> prevSpotNames = ctx.getPreviousSpotNames();
        if (prevSpotNames != null && !prevSpotNames.isEmpty()) {
            sb.append("前序检索结果中的景点（按顺序）：\n");
            for (int i = 0; i < prevSpotNames.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(prevSpotNames.get(i)).append("\n");
            }
        }

        // 最近讨论的实体
        String lastEntity = ctx.getLastDiscussedEntity();
        if (lastEntity != null && !lastEntity.isBlank()) {
            sb.append("最近讨论的实体：").append(lastEntity).append("\n");
        }

        return sb.toString();
    }

    /**
     * 快速判断查询是否可能需要指代消解。
     * <p>不含序数词、代词、隐式地点引用的查询直接跳过 LLM 调用。</p>
     */
    static boolean needsResolution(String query) {
        if (query == null || query.isBlank()) return false;
        // 序数词特征
        if (query.contains("第一") || query.contains("第二") || query.contains("第三")
                || query.contains("最后") || query.contains("上个") || query.contains("下个")
                || query.contains("前面") || query.contains("后面")) return true;
        // 代词特征
        if (query.contains("它") || query.contains("他") || query.contains("她")
                || query.contains("这个") || query.contains("那个") || query.contains("哪个")) return true;
        // 隐式地点/实体引用
        if (query.startsWith("附近") || query.startsWith("周边") || query.startsWith("旁边")
                || query.startsWith("同上") || query.equals("一样")) return true;
        // 超短查询可能是追问（如单字"呢"）
        if (query.length() <= 3 && (query.contains("呢") || query.contains("吗")
                || query.contains("吧") || query.contains("啊"))) return true;
        return false;
    }

    /**
     * 调用 LLM 进行指代消解。
     */
    private String resolveReferences(String query, String context) {
        String promptText = String.format(COMPRESSION_PROMPT, context, query);
        SystemMessage systemMsg = new SystemMessage("你是一个多轮对话查询消解专家。");
        UserMessage userMsg = new UserMessage(promptText);
        Prompt prompt = new Prompt(List.of(systemMsg, userMsg));

        ChatResponse response = chatModel.call(prompt);
        String result = response.getResult().getOutput().getText();

        if (result != null) {
            result = result.trim();
        }
        return result;
    }
}
