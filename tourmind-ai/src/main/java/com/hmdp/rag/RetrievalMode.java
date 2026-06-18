package com.hmdp.rag;

/**
 * 检索模式枚举 — 控制 HybridDocumentRetriever 走哪条检索管线。
 *
 * <h3>模式说明</h3>
 * <ul>
 *   <li><b>VECTOR_ONLY</b> — Qdrant 向量检索 → DB → 距离排序 → 父文档。适合简单事实查询。</li>
 *   <li><b>BM25_ONLY</b> — ES BM25 关键词检索 → DB → 距离排序 → 父文档。适合退票/规则等关键词密集文档。</li>
 *   <li><b>HYBRID_RRF</b> — 向量 + BM25 + RRF 融合 + 可选重排 + MMR。适合复杂推荐/对比查询。</li>
 * </ul>
 *
 * <p>QueryRouter 根据分类+复杂度给出默认值，LLM 在调用 searchKnowledgeBase 时可通过
 * retrievalMode 参数覆盖。</p>
 */
public enum RetrievalMode {
    /** 仅 Qdrant 向量检索 */
    VECTOR_ONLY,
    /** 仅 ES BM25 关键词检索 */
    BM25_ONLY,
    /** 混合检索 + RRF 融合 + 重排 + MMR（默认） */
    HYBRID_RRF
}
