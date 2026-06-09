package com.hmdp.rag.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Elasticsearch BM25 检索器 — 使用 ik_max_word 中文分词进行关键词检索。
 *
 * <h3>检索流程</h3>
 * <ol>
 *   <li>对 {@code chunk_text} 字段执行 match 查询（ES 内置 BM25 评分）</li>
 *   <li>按 spot_id 去重，保留每个景点的最高 BM25 分数</li>
 *   <li>返回按分数降序的 ScoredDoc 列表</li>
 * </ol>
 */
@Slf4j
public class EsBm25Retriever {

    private final ElasticsearchClient client;
    private final String indexName;

    public EsBm25Retriever(ElasticsearchClient client, String indexName) {
        this.client = client;
        this.indexName = indexName;
    }

    /**
     * ES BM25 关键词检索。
     *
     * @param query 查询字符串（建议是 RewriteQueryTransformer 改写后的关键词串）
     * @param topK  返回候选数
     * @return 按 BM25 分数降序排列的去重结果（每个 spotId 只保留最高分 chunk）
     */
    public List<RrfRankFuser.ScoredDoc> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        try {
            SearchResponse<Map> response = client.search(s -> s
                            .index(indexName)
                            .query(q -> q.match(m -> m
                                    .field("chunk_text")
                                    .query(query)
                            ))
                            .size(topK),
                    Map.class
            );

            List<Hit<Map>> hits = response.hits().hits();
            if (hits.isEmpty()) {
                log.debug("ES BM25 无结果: query={}", query);
                return Collections.emptyList();
            }

            log.debug("ES BM25 命中 {} 条: query={}", hits.size(), query);

            // 按 spot_id 去重，保留最高分
            Map<String, RrfRankFuser.ScoredDoc> bestBySpotId = new LinkedHashMap<>();

            for (Hit<Map> hit : hits) {
                Map<String, Object> source = hit.source();
                if (source == null) continue;

                Object spotIdObj = source.get("spot_id");
                String spotId = spotIdObj != null ? spotIdObj.toString() : null;
                if (spotId == null) continue;

                double score = hit.score() != null ? hit.score() : 0.0;

                // 去重：保留最高分
                RrfRankFuser.ScoredDoc existing = bestBySpotId.get(spotId);
                if (existing == null || score > existing.getScore()) {
                    String chunkText = Objects.toString(source.getOrDefault("chunk_text", ""), "");
                    String spotName = Objects.toString(source.getOrDefault("spot_name", ""), "");

                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("spotId", spotId);
                    metadata.put("spotName", spotName);
                    metadata.put("source", "es_bm25");

                    bestBySpotId.put(spotId, new RrfRankFuser.ScoredDoc(
                            hit.id(), spotId, score, chunkText, metadata));
                }
            }

            // 按分数降序排列
            List<RrfRankFuser.ScoredDoc> results = bestBySpotId.values().stream()
                    .sorted(Comparator.comparingDouble(RrfRankFuser.ScoredDoc::getScore).reversed())
                    .collect(Collectors.toList());

            log.debug("ES BM25 去重后 {} 个候选: query={}", results.size(), query);
            return results;

        } catch (Exception e) {
            log.error("ES BM25 检索异常: query={}, error={}", query, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
