package com.hmdp.rag.index;

import com.hmdp.entity.Spot;
import com.hmdp.rag.chunking.TicketRefundEnricher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;

/**
 * 退购票知识库索引器 — 将购票须知和退票条件写入 Qdrant + Elasticsearch。
 *
 * <h3>索引结构（无父子文档）</h3>
 * <ul>
 *   <li>每个景点最多 2 个子文档：{@code [购票须知]} 和 {@code [退票条件]}</li>
 *   <li>直接写入 Qdrant collection: {@code ticket_refund_kb}</li>
 *   <li>直接写入 ES index: {@code ticket_refund_chunks}</li>
 *   <li><b>不生成父文档</b> — 退购票知识以 chunk 粒度直接检索</li>
 * </ul>
 *
 * <h3>元数据</h3>
 * <p>每个 Document 携带：spotId、spotName、category（"ticket"/"refund"）</p>
 */
@Slf4j
public class TicketRefundIndexer {

    private final VectorStore vectorStore;
    private final EsChunkIndexer esChunkIndexer;
    private final TicketRefundEnricher enricher;

    public TicketRefundIndexer(VectorStore vectorStore, EsChunkIndexer esChunkIndexer) {
        this.vectorStore = vectorStore;
        this.esChunkIndexer = esChunkIndexer;
        this.enricher = new TicketRefundEnricher();
    }

    /**
     * 将一个景点的购退票信息索引到知识库。
     *
     * @param spot 景点实体
     * @return 写入的文档数量（0~2）
     */
    public int indexSpot(Spot spot) {
        Long spotId = spot.getId();

        // 先清理旧数据，保证幂等
        deleteBySpotId(spotId);

        // 构建文档
        List<Document> docs = enricher.build(spot);
        if (docs.isEmpty()) {
            log.debug("Spot {} 无购退票信息，跳过索引", spotId);
            return 0;
        }

        // 写入 Qdrant
        vectorStore.add(docs);
        log.debug("Spot {} 的 {} 个购退票文档已写入 Qdrant (ticket_refund_kb)", spotId, docs.size());

        // 写入 ES
        esChunkIndexer.indexChunks(spotId, spot.getName(), docs);
        log.debug("Spot {} 的 {} 个购退票文档已写入 ES (ticket_refund_chunks)", spotId, docs.size());

        log.info("TicketRefundIndexer 完成索引: spotId={}, docCount={}", spotId, docs.size());
        return docs.size();
    }

    /**
     * 清理一个景点的所有购退票索引数据。
     */
    public void deleteBySpotId(Long spotId) {
        // 清理 Qdrant
        try {
            vectorStore.delete(new FilterExpressionBuilder()
                    .eq(TicketRefundEnricher.META_SPOT_ID, spotId.toString())
                    .build());
        } catch (Exception e) {
            log.warn("TicketRefundIndexer Qdrant 删除异常: spotId={}, error={}", spotId, e.getMessage());
        }

        // 清理 ES
        esChunkIndexer.deleteBySpotId(spotId);
    }
}
