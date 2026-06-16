package com.hmdp.rag.retrieval;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MMR（Maximal Marginal Relevance）多样性重排器。
 *
 * <h3>公式</h3>
 * <pre>MMR(doc) = λ × relevance(doc) - (1-λ) × max_{selected}(similarity(doc, selected))</pre>
 *
 * <h3>用途</h3>
 * <p>在 RRF 融合后、最终返回前，对候选结果进行多样性重排，
 * 避免返回 5 个都是同类型/同区域的景点。</p>
 *
 * <h3>多样性维度</h3>
 * <ul>
 *   <li><b>typeName</b> — 景点类型（自然风景区/文化古迹/主题公园...）</li>
 *   <li><b>area</b> — 所在区域（西湖区/余杭区...）</li>
 * </ul>
 */
public class MmrDiversifier {

    /** 相关性-多样性权衡参数（0=全多样性, 1=全相关性） */
    private final double lambda;

    public MmrDiversifier(double lambda) {
        this.lambda = lambda;
    }

    /**
     * 对融合结果进行 MMR 多样性重排。
     *
     * @param fused     RRF 融合后的候选列表（按 RRF 分数降序）
     * @param topK      最终返回数量
     * @return 多样性重排后的结果
     */
    public List<RrfRankFuser.FusedResult> diversify(
            List<RrfRankFuser.FusedResult> fused, int topK) {

        if (fused == null || fused.size() <= 1 || topK <= 1) {
            return fused != null ? fused : Collections.emptyList();
        }

        List<RrfRankFuser.FusedResult> remaining = new ArrayList<>(fused);
        List<RrfRankFuser.FusedResult> selected = new ArrayList<>();

        // 第一个选最高分的（保证最相关的不丢失）
        selected.add(remaining.remove(0));

        while (selected.size() < topK && !remaining.isEmpty()) {
            double bestMmr = Double.NEGATIVE_INFINITY;
            int bestIdx = -1;

            for (int i = 0; i < remaining.size(); i++) {
                RrfRankFuser.FusedResult candidate = remaining.get(i);
                double relevance = candidate.getRrfScore();
                double maxSim = maxSimilarity(candidate, selected);
                double mmr = lambda * relevance - (1.0 - lambda) * maxSim;

                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    bestIdx = i;
                }
            }

            if (bestIdx >= 0) {
                selected.add(remaining.remove(bestIdx));
            }
        }

        // 追加剩余的（如果 topK > fused.size() 或 MMR 选满后还有）
        selected.addAll(remaining);

        return selected.stream().limit(topK).collect(Collectors.toList());
    }

    /**
     * 计算候选文档与已选集合的最大相似度。
     */
    private double maxSimilarity(RrfRankFuser.FusedResult candidate,
                                  List<RrfRankFuser.FusedResult> selected) {
        return selected.stream()
                .mapToDouble(s -> similarity(candidate, s))
                .max()
                .orElse(0.0);
    }

    /**
     * 计算两个文档在多样性维度上的相似度。
     * 相同 type → +0.5, 相同 area → +0.5, 范围 [0, 1]。
     */
    private double similarity(RrfRankFuser.FusedResult a, RrfRankFuser.FusedResult b) {
        double sim = 0.0;
        String typeA = (String) a.getMetadata().get("typeName");
        String typeB = (String) b.getMetadata().get("typeName");
        if (typeA != null && typeA.equals(typeB)) sim += 0.5;

        String areaA = (String) a.getMetadata().get("area");
        String areaB = (String) b.getMetadata().get("area");
        if (areaA != null && areaA.equals(areaB)) sim += 0.5;

        return sim;
    }
}
