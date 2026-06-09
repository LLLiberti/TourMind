package com.hmdp.rag.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.JsonData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.util.*;

/**
 * Elasticsearch 索引管理器 — 将景点知识子文档分块存入 ES，支持 ik_max_word 中文分词。
 *
 * <h3>索引 Mapping</h3>
 * <ul>
 *   <li>{@code chunk_text} — text 类型，ik_max_word 分词器（BM25 检索字段）</li>
 *   <li>{@code spot_id} — keyword 类型（按景点筛选/删除）</li>
 *   <li>{@code spot_name} — text 类型</li>
 *   <li>{@code chunk_topic} — keyword 类型（语义主题标签）</li>
 *   <li>{@code chunk_index} — integer 类型</li>
 * </ul>
 */
@Slf4j
public class EsChunkIndexer {

    private final ElasticsearchClient client;
    private final String indexName;

    public EsChunkIndexer(ElasticsearchClient client, String indexName) {
        this.client = client;
        this.indexName = indexName;
    }

    /**
     * 确保索引存在，不存在则创建（含 ik_max_word 分析器配置）。
     */
    public void createIndexIfNotExists() {
        try {
            ExistsRequest existsRequest = ExistsRequest.of(e -> e.index(indexName));
            boolean exists = client.indices().exists(existsRequest).value();
            if (exists) {
                log.info("ES 索引已存在: {}", indexName);
                return;
            }

            client.indices().create(c -> c
                    .index(indexName)
                    .settings(s -> s
                            .analysis(a -> a
                                    .analyzer("ik_max_word_analyzer", az -> az
                                            .custom(cu -> cu.tokenizer("ik_max_word"))
                                    )
                            )
                    )
                    .mappings(m -> m
                            .properties("chunk_text", p -> p
                                    .text(t -> t.analyzer("ik_max_word_analyzer")))
                            .properties("spot_id", p -> p.keyword(k -> k))
                            .properties("spot_name", p -> p
                                    .text(t -> t.analyzer("ik_max_word_analyzer")))
                            .properties("chunk_topic", p -> p.keyword(k -> k))
                            .properties("chunk_index", p -> p.integer(i -> i))
                    )
            );

            log.info("ES 索引已创建: {} (ik_max_word)", indexName);

        } catch (Exception e) {
            log.error("创建 ES 索引失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 批量索引一个景点的所有子文档分块。
     *
     * @param spotId   景点 ID
     * @param spotName 景点名称
     * @param chunks   子文档分块列表（来自 SpotKnowledgeSplitter）
     */
    public void indexChunks(Long spotId, String spotName, List<Document> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            log.debug("Spot {} 无分块，跳过 ES 索引", spotId);
            return;
        }

        createIndexIfNotExists();

        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();

            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                String docId = spotId + ":" + i;

                Map<String, Object> docMap = new LinkedHashMap<>();
                docMap.put("chunk_text", chunk.getText());
                docMap.put("spot_id", spotId.toString());
                docMap.put("spot_name", spotName);
                docMap.put("chunk_topic",
                        chunk.getMetadata().getOrDefault("chunkTopic", "未知").toString());
                docMap.put("chunk_index", i);

                final int idx = i;
                bulkBuilder.operations(op -> op
                        .index(idxOp -> idxOp
                                .index(indexName)
                                .id(docId)
                                .document(docMap)
                        )
                );
            }

            BulkResponse response = client.bulk(bulkBuilder.build());

            int failed = 0;
            for (BulkResponseItem item : response.items()) {
                if (item.error() != null) {
                    log.warn("ES 索引失败: docId={}, error={}", item.id(), item.error().reason());
                    failed++;
                }
            }

            log.info("ES 索引完成: spotId={}, chunks={}, failed={}", spotId, chunks.size(), failed);

        } catch (Exception e) {
            log.error("ES 批量索引异常: spotId={}, error={}", spotId, e.getMessage(), e);
        }
    }

    /**
     * 按 spotId 删除 ES 索引中的所有相关文档。
     */
    public void deleteBySpotId(Long spotId) {
        try {
            client.deleteByQuery(d -> d
                    .index(indexName)
                    .query(q -> q.term(t -> t
                            .field("spot_id")
                            .value(spotId.toString())
                    ))
            );
            log.debug("ES 索引已删除: spotId={}", spotId);
        } catch (Exception e) {
            log.warn("ES 删除异常: spotId={}, error={}", spotId, e.getMessage());
        }
    }
}
