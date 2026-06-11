package com.hmdp.rag.evaluation;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * CRAG 检索质量评估器 — 基于相似度分数判断检索结果是否可信。
 *
 * <h3>评估逻辑</h3>
 * <pre>
 * 取所有文档的最高相似度分数：
 *   >= confidentThreshold  → CONFIDENT    （正常使用检索结果）
 *   >= ambiguousThreshold  → AMBIGUOUS    （使用但提醒 LLM 信息可能不完整）
 *   <  ambiguousThreshold  → INSUFFICIENT （丢弃检索结果，LLM + 免责声明回答）
 * </pre>
 *
 * <h3>CRAG 参考</h3>
 * <p>Corrective Retrieval Augmented Generation (Yan et al., 2024):
 * 当检索质量不可靠时自动回退，减少幻觉。</p>
 */
public class RetrievalEvaluator {

    /** 置信度等级 */
    public enum Confidence {
        /** 高可信 — 检索结果充分相关 */
        CONFIDENT,
        /** 模糊 — 部分相关，需告知 LLM 注意 */
        AMBIGUOUS,
        /** 不足 — 检索无有效结果，应回退 */
        INSUFFICIENT
    }

    /** 评估结果 */
    public static class EvaluationResult {
        private final Confidence confidence;
        private final double maxScore;

        public EvaluationResult(Confidence confidence, double maxScore) {
            this.confidence = confidence;
            this.maxScore = maxScore;
        }

        public Confidence getConfidence() { return confidence; }
        public double getMaxScore() { return maxScore; }

        public boolean isConfident() { return confidence == Confidence.CONFIDENT; }
        public boolean isAmbiguous() { return confidence == Confidence.AMBIGUOUS; }
        public boolean isInsufficient() { return confidence == Confidence.INSUFFICIENT; }
    }

    /** 用于 LLM 的提示文本 */
    public static final String AMBIGUOUS_HINT =
            "（注意：以下景点信息可能与用户问题部分相关，请根据实际情况判断后回答。）";

    public static final String INSUFFICIENT_HINT =
            "⚠️ 未找到与用户问题直接匹配的景点信息。请根据你的知识回答以下问题，" +
            "但必须在回答开头加上：\"以下信息基于一般知识，建议以景区官方信息为准。\"";

    private final double confidentThreshold;
    private final double ambiguousThreshold;

    /**
     * @param confidentThreshold 大于等于此值视为高可信（默认 0.6）
     * @param ambiguousThreshold 大于等于此值视为模糊（默认 0.4），低于此值为不足
     */
    public RetrievalEvaluator(double confidentThreshold, double ambiguousThreshold) {
        this.confidentThreshold = confidentThreshold;
        this.ambiguousThreshold = ambiguousThreshold;
    }

    /**
     * 评估检索结果的质量。
     *
     * @param documents 检索到的文档列表
     * @return 评估结果
     */
    public EvaluationResult evaluate(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return new EvaluationResult(Confidence.INSUFFICIENT, 0.0);
        }

        double maxScore = 0.0;
        for (Document doc : documents) {
            double score = extractScore(doc);
            if (score > maxScore) {
                maxScore = score;
            }
        }

        if (maxScore >= confidentThreshold) {
            return new EvaluationResult(Confidence.CONFIDENT, maxScore);
        } else if (maxScore >= ambiguousThreshold) {
            return new EvaluationResult(Confidence.AMBIGUOUS, maxScore);
        } else {
            return new EvaluationResult(Confidence.INSUFFICIENT, maxScore);
        }
    }

    /**
     * 从 Document 元数据中提取相似度分数。
     * Qdrant 和 RRF 融合后分数存储在 metadata 的 "score" 或 "distance" 字段中。
     * distance 字段需要转换为相似度分数（1 / (1 + distance)）。
     */
    private double extractScore(Document doc) {
        // 优先取 metadata 中的 score
        Object scoreObj = doc.getMetadata().get("score");
        if (scoreObj instanceof Number num) {
            return num.doubleValue();
        }

        // distance → 相似度转换
        Object distObj = doc.getMetadata().get("distance");
        if (distObj instanceof Number num) {
            return 1.0 / (1.0 + num.doubleValue());
        }

        // 无分数信息 — 无法判定质量，返回 0 触发 INSUFFICIENT（宁可保守不盲目信任）
        return 0.0;
    }
}
