package com.hmdp.rag.chunking;

import com.hmdp.entity.Spot;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 退购票知识文本构建器 — 从 Spot 实体提取购票须知和退票条件，构建独立的子文档。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li><b>不入主知识库</b> — 购票须知和退票条件是专项信息，存入独立的退购票知识库</li>
 *   <li>每个景点最多生成 2 个 Document：{@code [购票须知]} 和 {@code [退票条件]}</li>
 *   <li>字段为空时不生成对应 Document</li>
 *   <li>无状态纯函数，线程安全</li>
 * </ul>
 *
 * <h3>输出格式</h3>
 * <pre>{@code
 * [购票须知]
 * 门票40元/人，凭身份证实名购票入园...
 *
 * [退票条件]
 * 未使用的门票在购买后7天内可申请退款...
 * }</pre>
 *
 * <h3>元数据</h3>
 * <p>每个 Document 携带：spotId、spotName、category（"ticket" 或 "refund"）</p>
 */
public class TicketRefundEnricher {

    public static final String CATEGORY_TICKET = "ticket";
    public static final String CATEGORY_REFUND = "refund";

    public static final String META_SPOT_ID = "spotId";
    public static final String META_SPOT_NAME = "spotName";
    public static final String META_CATEGORY = "category";

    /**
     * 从 Spot 实体构建购票须知和退票条件的子文档列表。
     *
     * @param spot 景点实体
     * @return Document 列表（0~2 个），字段为空时不生成对应 Document
     */
    public List<Document> build(Spot spot) {
        List<Document> docs = new ArrayList<>(2);

        // 基础元数据
        Map<String, Object> baseMeta = new HashMap<>();
        baseMeta.put(META_SPOT_ID, spot.getId().toString());
        baseMeta.put(META_SPOT_NAME, spot.getName());

        // [购票须知]
        if (spot.getTicketNotice() != null && !spot.getTicketNotice().isBlank()) {
            Map<String, Object> ticketMeta = new HashMap<>(baseMeta);
            ticketMeta.put(META_CATEGORY, CATEGORY_TICKET);
            String text = "[购票须知]\n" + spot.getTicketNotice().trim();
            docs.add(new Document(text, ticketMeta));
        }

        // [退票条件]
        if (spot.getRefundPolicy() != null && !spot.getRefundPolicy().isBlank()) {
            Map<String, Object> refundMeta = new HashMap<>(baseMeta);
            refundMeta.put(META_CATEGORY, CATEGORY_REFUND);
            String text = "[退票条件]\n" + spot.getRefundPolicy().trim();
            docs.add(new Document(text, refundMeta));
        }

        return docs;
    }
}
