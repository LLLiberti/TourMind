package com.hmdp.rag.retrieval;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RRF（Reciprocal Rank Fusion）排名融合器 — 将 ES BM25 和 Qdrant 向量两路排名结果融合。
 *
 * <h3>RRF 公式</h3>
 * <pre>RRF_score(doc) = Σ 1/(k + rank_i(doc))</pre>
 * <ul>
 *   <li>k：平滑常数，默认 60</li>
 *   <li>rank_i(doc)：文档在第 i 路结果中的排名（1-indexed）</li>
 * </ul>
 *
 * <h3>优势</h3>
 * <ul>
 *   <li>无需分数归一化（min-max / z-score），仅依赖排名</li>
 *   <li>对异常值不敏感，两路分数分布差异不影响结果</li>
 *   <li>计算简单，无需额外参数调优</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * RrfRankFuser fuser = new RrfRankFuser(60);
 * List<ScoredDoc> esResults = esBm25Retriever.search(query, 20);
 * List<ScoredDoc> vecResults = hybridDocumentRetriever.vectorSearch(query, 20);
 * List<FusedResult> merged = fuser.fuse(esResults, vecResults, 20);
 * }</pre>
 */
public class RrfRankFuser {

    private final int rankConstant;

    public RrfRankFuser(int rankConstant) {
        this.rankConstant = rankConstant;
    }

    /**
     * 对两路排名结果进行 RRF 融合。
     *
     * @param esResults     ES BM25 检索结果（已按分数降序排列）
     * @param vectorResults Qdrant 向量检索结果（已按相似度降序排列）
     * @param topK          返回结果数上限
     * @return 按 RRF 分数降序排列的融合结果
     */
    public List<FusedResult> fuse(List<ScoredDoc> esResults,
                                   List<ScoredDoc> vectorResults,
                                   int topK) {

        // spotId → 累加 RRF 分数
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        // spotId → 文本和元数据（用于后续父文档加载）
        Map<String, String> textMap = new HashMap<>();
        Map<String, Map<String, Object>> metadataMap = new HashMap<>();

        // 处理 ES BM25 排名
        accumulateRrf(esResults, rrfScores, textMap, metadataMap);

        // 处理 Qdrant 向量排名
        accumulateRrf(vectorResults, rrfScores, textMap, metadataMap);

        // 按 RRF 分数降序排列
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    String spotId = entry.getKey();
                    return new FusedResult(spotId, entry.getValue(),
                            textMap.get(spotId), metadataMap.get(spotId));
                })
                .collect(Collectors.toList());
    }

    private void accumulateRrf(List<ScoredDoc> results,
                                Map<String, Double> rrfScores,
                                Map<String, String> textMap,
                                Map<String, Map<String, Object>> metadataMap) {
        for (int i = 0; i < results.size(); i++) {
            ScoredDoc doc = results.get(i);
            String spotId = doc.getSpotId();
            if (spotId == null || spotId.isEmpty()) continue;

            // rank 从 1 开始（1-indexed）
            double rrf = 1.0 / (rankConstant + i + 1);
            rrfScores.merge(spotId, rrf, Double::sum);

            // 保留文本和元数据
            textMap.putIfAbsent(spotId, doc.getText());
            metadataMap.putIfAbsent(spotId, doc.getMetadata());
        }
    }

    // ==================== 数据类 ====================

    /** 统一的检索结果文档 */
    public static class ScoredDoc {
        private final String docId;
        private final String spotId;
        private final double score;
        private final String text;
        private final Map<String, Object> metadata;

        public ScoredDoc(String docId, String spotId, double score, String text,
                         Map<String, Object> metadata) {
            this.docId = docId;
            this.spotId = spotId;
            this.score = score;
            this.text = text;
            this.metadata = metadata != null ? metadata : Collections.emptyMap();
        }

        public String getDocId() { return docId; }
        public String getSpotId() { return spotId; }
        public double getScore() { return score; }
        public String getText() { return text; }
        public Map<String, Object> getMetadata() { return metadata; }
    }

    /** RRF 融合后的结果 */
    public static class FusedResult {
        private final String spotId;
        private final double rrfScore;
        private final String text;
        private final Map<String, Object> metadata;

        public FusedResult(String spotId, double rrfScore, String text,
                           Map<String, Object> metadata) {
            this.spotId = spotId;
            this.rrfScore = rrfScore;
            this.text = text;
            this.metadata = metadata != null ? metadata : Collections.emptyMap();
        }

        public String getSpotId() { return spotId; }
        public double getRrfScore() { return rrfScore; }
        public String getText() { return text; }
        public Map<String, Object> getMetadata() { return metadata; }

        @Override
        public String toString() {
            return "FusedResult{spotId='" + spotId + "', rrf=" + String.format("%.6f", rrfScore) + '}';
        }
    }
}
