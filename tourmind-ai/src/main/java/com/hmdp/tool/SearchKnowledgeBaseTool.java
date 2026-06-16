package com.hmdp.tool;

import com.hmdp.rag.cache.RetrievalCacheManager;
import com.hmdp.rag.evaluation.CragCorrector;
import com.hmdp.rag.generation.GenerationGuard;
import com.hmdp.rag.query.CompressionQueryTransformer;
import com.hmdp.rag.query.RewriteQueryTransformer;
import com.hmdp.rag.retrieval.HybridDocumentRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识库检索工具 — 封装 RAG 管线为 Agent 可调用的工具。
 *
 * <p>内部调用 RewriteQueryTransformer → HybridDocumentRetriever，
 * 将检索结果格式化为含置信度信号的文本，供 LLM Agent 观察和决策。</p>
 *
 * <h3>输出格式</h3>
 * <p>CONFIDENT/AMBIGUOUS/INSUFFICIENT 三种置信度各附带不同的行动建议，
 * 引导 Agent 决定是直接使用结果、谨慎使用还是重新检索。</p>
 */
@Slf4j
@Component
public class SearchKnowledgeBaseTool {

    @Resource
    private HybridDocumentRetriever retriever;

    @Resource
    private CompressionQueryTransformer compressor;

    @Resource
    private RewriteQueryTransformer rewriter;

    @Resource
    private RetrievalCacheManager cacheManager;

    @Resource
    private CragCorrector cragCorrector;

    @Resource
    private GenerationGuard generationGuard;

    /** 最多展示的文档数 */
    private static final int MAX_DOCS_TO_SHOW = 5;

    /**
     * 执行知识库检索（P1 增强版：缓存 + CRAG + Citation）。
     *
     * <p>管线：缓存查询 → 指代消解 → 查询改写 → 混合检索 → CRAG纠正 → Citation注入</p>
     */
    public String execute(String queryJson) {
        String query = extractQuery(queryJson);
        if (query == null || query.isBlank()) {
            return "[检索结果] 查询参数为空，请提供有效的检索关键词。";
        }

        log.info("SearchKB: query='{}'", query);

        // 0. 缓存查询
        if (cacheManager != null) {
            String cached = cacheManager.get(query);
            if (cached != null) {
                log.info("SearchKB: 缓存命中");
                return cached;
            }
        }

        Query q = Query.builder().text(query).build();

        // 1. 多轮指代消解
        if (compressor != null) {
            q = compressor.transform(q);
            log.debug("SearchKB: compressed='{}'", q.text());
        }

        // 2. Query 改写
        Query rewritten = rewriter.transform(q);
        log.debug("SearchKB: rewritten='{}'", rewritten.text());

        // 3. 混合检索
        List<Document> docs = retriever.retrieve(rewritten);
        List<Query> expandedQueries = rewriter.getLastExpandedQueries();
        if (expandedQueries != null && !expandedQueries.isEmpty()) {
            docs = mergeMultiQueryResults(docs, expandedQueries);
        }

        // 4. 读取置信度
        String confidence = retriever.getLastRetrievalConfidence();
        double maxScore = retriever.getLastMaxRetrievalScore();

        // 5. 【P1 CRAG 纠正】检索不足时自动纠正
        if ("INSUFFICIENT".equals(confidence) && cragCorrector != null) {
            CragCorrector.CorrectionResult cr = cragCorrector.correct(query, maxScore);
            if (!cr.getCorrectedQuery().equals(query)) {
                log.info("CRAG: 用修正查询重新检索 '{}'", cr.getCorrectedQuery());
                Query correctedQ = Query.builder().text(cr.getCorrectedQuery()).build();
                List<Document> reDocs = retriever.retrieve(correctedQ);
                if (!reDocs.isEmpty()) {
                    docs = reDocs;
                    confidence = retriever.getLastRetrievalConfidence();
                    maxScore = retriever.getLastMaxRetrievalScore();
                }
            }
            // Web 回退结果追加
            if (!cr.getWebSnippets().isEmpty()) {
                for (String snippet : cr.getWebSnippets()) {
                    docs.add(new Document("[Web补充] " + snippet,
                            Collections.singletonMap("source", "web")));
                }
                confidence = "AMBIGUOUS"; // web 结果不可完全信任
                log.info("CRAG: 追加 {} 条 web 摘要", cr.getWebSnippets().size());
            }
        }

        // 6. 格式化输出（含 Citation 指令）
        String result = formatResults(docs, confidence, maxScore);

        // 7. 写入缓存
        if (cacheManager != null) {
            cacheManager.put(query, result);
        }

        return result;
    }

    /**
     * 合并多查询视角的检索结果（去重 + 保留原始排序）。
     */
    private List<Document> mergeMultiQueryResults(List<Document> primaryDocs,
                                                   List<Query> expandedQueries) {
        List<Document> allDocs = new ArrayList<>(primaryDocs);
        Set<String> seen = new LinkedHashSet<>();
        for (Document doc : primaryDocs) {
            String spotId = (String) doc.getMetadata().get("spotId");
            if (spotId != null) seen.add(spotId);
        }
        for (Query eq : expandedQueries) {
            List<Document> extra = retriever.retrieve(eq);
            for (Document doc : extra) {
                String spotId = (String) doc.getMetadata().get("spotId");
                if (spotId == null || seen.add(spotId)) {
                    allDocs.add(doc);
                }
            }
        }
        return allDocs;
    }

    /**
     * 从 JSON 参数中提取 query 字段。
     */
    private String extractQuery(String jsonArgs) {
        if (jsonArgs == null || jsonArgs.isBlank()) {
            return null;
        }
        // 简单 JSON 解析（不引入额外依赖）
        String key = "\"query\"";
        int keyIdx = jsonArgs.indexOf(key);
        if (keyIdx < 0) {
            return jsonArgs.trim(); // 可能是纯文本
        }
        int colonIdx = jsonArgs.indexOf(':', keyIdx + key.length());
        if (colonIdx < 0) {
            return jsonArgs.trim();
        }
        int startQuote = jsonArgs.indexOf('"', colonIdx + 1);
        if (startQuote < 0) {
            return jsonArgs.substring(colonIdx + 1).trim();
        }
        int endQuote = jsonArgs.indexOf('"', startQuote + 1);
        if (endQuote < 0) {
            return jsonArgs.substring(startQuote + 1).trim();
        }
        return jsonArgs.substring(startQuote + 1, endQuote);
    }

    /**
     * 格式化检索结果为结构化文本，包含置信度引导。
     */
    private String formatResults(List<Document> docs, String confidence, double maxScore) {
        StringBuilder sb = new StringBuilder();

        if (docs == null || docs.isEmpty()) {
            sb.append("[检索结果] 找到 0 篇文档 | 置信度: INSUFFICIENT | 最高分: ").append(maxScore).append("\n\n");
            sb.append("[建议] 未找到匹配信息。可以：\n");
            sb.append("  1. 用不同的关键词重新检索（缩短或换近义词）\n");
            sb.append("  2. 直接告知用户未找到相关信息，基于通用知识回答并加免责声明\n");
            return sb.toString();
        }

        sb.append("[检索结果] 找到 ").append(docs.size()).append(" 篇文档")
          .append(" | 置信度: ").append(confidence)
          .append(" | 最高分: ").append(String.format("%.2f", maxScore))
          .append("\n\n");

        // Citation 注入指令
        sb.append("[引用] 请在引用以下文档信息时标注编号，例如 [1]。\n\n");

        // 列出文档（最多 5 条）
        int showCount = Math.min(docs.size(), MAX_DOCS_TO_SHOW);
        for (int i = 0; i < showCount; i++) {
            Document doc = docs.get(i);
            sb.append("--- 文档 [").append(i + 1).append("] ---\n");
            String text = truncate(doc.getText(), 400);
            sb.append(text).append("\n");
        }

        if (docs.size() > MAX_DOCS_TO_SHOW) {
            sb.append("... 还有 ").append(docs.size() - MAX_DOCS_TO_SHOW).append(" 篇文档\n");
        }

        // 置信度引导
        sb.append("\n[建议] ");
        switch (confidence) {
            case "CONFIDENT" ->
                sb.append("结果置信度高，可直接用于回答用户问题。如需价格/天气，请调用对应工具。");
            case "AMBIGUOUS" ->
                sb.append("结果可能不完全匹配。如信息不足，可换关键词重新检索或谨慎回答。");
            default ->
                sb.append("结果不足。请尝试用不同关键词重新检索，或如实告知用户未找到。");
        }

        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
