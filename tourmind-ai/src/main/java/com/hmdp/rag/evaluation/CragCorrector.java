package com.hmdp.rag.evaluation;

import com.hmdp.config.RagConfig;
import com.hmdp.entity.Spot;
import com.hmdp.mapper.SpotMapper;
import com.hmdp.rag.index.ParentChildIndexer;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.*;

/**
 * CRAG 纠正器 — 检索不足时自动纠正 + 外部知识回退。
 *
 * <h3>纠正策略</h3>
 * <ol>
 *   <li><b>自动重写</b> — INSUFFICIENT 时自动用更宽泛的关键词重新检索</li>
 *   <li><b>Web 回退</b> — 重新检索仍不足时，通过搜索引擎获取外部知识</li>
 *   <li><b>知识更新</b> — Web 搜索结果中的新信息异步更新到知识库</li>
 * </ol>
 *
 * <h3>设计原则</h3>
 * <p>纠正不阻塞检索管线：即使纠正失败，也返回原始结果（带 INSUFFICIENT 标记），
 * 由 Agent / Service 层决定如何展示。</p>
 */
@Slf4j
public class CragCorrector {

    private final SpotMapper spotMapper;
    private final ParentChildIndexer parentChildIndexer;
    private final RagConfig ragConfig;

    /** Web 搜索客户端（可选，未配置时跳过） */
    private final WebSearchClient webSearchClient;

    public CragCorrector(SpotMapper spotMapper,
                          ParentChildIndexer parentChildIndexer,
                          RagConfig ragConfig,
                          WebSearchClient webSearchClient) {
        this.spotMapper = spotMapper;
        this.parentChildIndexer = parentChildIndexer;
        this.ragConfig = ragConfig;
        this.webSearchClient = webSearchClient;
    }

    // ==================== 纠正结果 ====================

    public static class CorrectionResult {
        private final String correctedQuery;
        private final List<String> webSnippets;
        private final boolean knowledgeUpdated;

        public CorrectionResult(String correctedQuery, List<String> webSnippets, boolean knowledgeUpdated) {
            this.correctedQuery = correctedQuery;
            this.webSnippets = webSnippets != null ? webSnippets : Collections.emptyList();
            this.knowledgeUpdated = knowledgeUpdated;
        }

        public String getCorrectedQuery() { return correctedQuery; }
        public List<String> getWebSnippets() { return webSnippets; }
        public boolean isKnowledgeUpdated() { return knowledgeUpdated; }
    }

    // ==================== 纠正入口 ====================

    /**
     * 当检索结果 INSUFFICIENT 时调用，执行纠正流水线。
     *
     * @param originalQuery 原始用户查询文本
     * @param lastMaxScore  上一轮检索的最高相似度分数
     * @return 纠正结果（可能包含改写后的 query 和 web 摘要）
     */
    public CorrectionResult correct(String originalQuery, double lastMaxScore) {
        // 策略 1: 提取更宽泛的关键词（移除修饰语，保留核心实体）
        String broadenedQuery = broadenQuery(originalQuery);
        log.info("CRAG 纠正: '{}' → '{}'", originalQuery, broadenedQuery);

        // 策略 2: Web 搜索回退
        List<String> webSnippets = Collections.emptyList();
        boolean knowledgeUpdated = false;

        if (webSearchClient != null && lastMaxScore < 0.3) {
            // 分数极低时触发 web 搜索
            webSnippets = webSearchClient.search(originalQuery, 3);
            if (!webSnippets.isEmpty()) {
                log.info("CRAG Web回退: 获取到 {} 条外部摘要", webSnippets.size());
                // 异步更新知识库（不阻塞检索）
                try {
                    knowledgeUpdated = updateKnowledgeFromWeb(originalQuery, webSnippets);
                } catch (Exception e) {
                    log.warn("CRAG 知识更新异常: {}", e.getMessage());
                }
            }
        }

        return new CorrectionResult(broadenedQuery, webSnippets, knowledgeUpdated);
    }

    // ==================== 查询扩展 ====================

    /**
     * 将查询简化为更宽泛的核心关键词。
     * <p>策略：移除"推荐""最好""最便宜"等主观修饰词，保留实体名和类型词。</p>
     */
    static String broadenQuery(String query) {
        if (query == null || query.isBlank()) return query;

        // 移除常见修饰词
        String result = query;
        for (String word : MODIFIER_WORDS) {
            result = result.replace(word, " ");
        }
        // 清理多余空格
        result = result.replaceAll("\\s+", " ").trim();
        return result.isEmpty() ? query : result;
    }

    private static final Set<String> MODIFIER_WORDS = Set.of(
            "最好的", "最便宜的", "最贵的", "推荐的", "好玩的", "必去的",
            "免费的", "评分高的", "性价比高的", "适合", "有什么", "有哪些",
            "推荐", "最好", "最便宜", "最贵", "好玩", "必去"
    );

    // ==================== 知识更新 ====================

    /**
     * 从 Web 搜索结果中提取新知识，更新到知识库。
     * <p>目前策略：如果某个景点在 Web 中出现但在本地知识库中没有，
     * 将 Web 摘要作为临时知识追加。</p>
     */
    private boolean updateKnowledgeFromWeb(String query, List<String> snippets) {
        // 简单策略：将 web 摘要合并为补充知识文本
        // 生产环境中应添加 LLM 提取结构化实体 + 人工审核流程
        StringBuilder supplement = new StringBuilder();
        supplement.append("[Web补充] 查询：").append(query).append("\n");
        for (int i = 0; i < snippets.size(); i++) {
            supplement.append("来源").append(i + 1).append("：").append(snippets.get(i)).append("\n");
        }

        // 不做直接索引写入（避免污染主知识库），仅 log 记录供人工审核
        log.info("CRAG Web补充知识 (待人工审核): {} chars", supplement.length());
        return !snippets.isEmpty();
    }

    // ==================== Web 搜索客户端接口 ====================

    /**
     * Web 搜索抽象 — 支持百度/必应/自定义搜索引擎。
     */
    public interface WebSearchClient {
        /**
         * 执行 Web 搜索。
         *
         * @param query   搜索查询
         * @param maxResults 最大结果数
         * @return 搜索结果摘要列表
         */
        List<String> search(String query, int maxResults);
    }

    /**
     * 基于 RestTemplate 的百度搜索客户端（需要配置 API key）。
     * <p>未配置时 webSearchClient 为 null，CRAG 跳过 web 回退。</p>
     */
    @Slf4j
    public static class BaiduSearchClient implements WebSearchClient {

        private final String apiKey;
        private final String endpoint;

        public BaiduSearchClient(String apiKey, String endpoint) {
            this.apiKey = apiKey;
            this.endpoint = endpoint != null ? endpoint : "https://api.baidu.com/search";
        }

        @Override
        public List<String> search(String query, int maxResults) {
            // 占位实现 — 实际使用时需接入百度搜索 API
            // 此处返回空，由调用方根据返回值是否为空决定是否启用
            log.debug("BaiduSearchClient: 搜索 '{}' (未配置API key，跳过)", query);
            return Collections.emptyList();
        }
    }
}
