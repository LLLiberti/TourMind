package com.hmdp.rag.client;

import com.hmdp.config.RagConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * qwen3-rerank HTTP 客户端 — 两阶段精排的交叉编码器重排序。
 *
 * <h3>API 格式</h3>
 * <pre>{@code
 * POST {endpoint}/rerank
 * Content-Type: application/json
 * {
 *   "query": "用户原始问题",
 *   "documents": ["文档1文本", "文档2文本", ...],
 *   "top_n": 10,
 *   "model": "qwen3-rerank"
 * }
 *
 * 响应:
 * {
 *   "results": [
 *     {"index": 0, "relevance_score": 0.95},
 *     {"index": 1, "relevance_score": 0.23}
 *   ]
 * }
 * }</pre>
 *
 * <h3>错误处理</h3>
 * <p>所有异常被捕获并返回 null，调用方跳过重排步骤，确保检索不中断。</p>
 */
@Slf4j
public class QwenRerankClient {

    private final RagConfig.RerankerConfig config;
    private final RestTemplate restTemplate;

    public QwenRerankClient(RagConfig.RerankerConfig config) {
        this.config = config;
        this.restTemplate = new RestTemplate();
        log.info("QwenRerankClient 初始化: endpoint={}, model={}, topK={}, threshold={}",
                config.getEndpoint(), config.getModel(), config.getTopK(), config.getScoreThreshold());
    }

    /**
     * 对候选文档进行重排序。
     *
     * @param query     用户原始问题（非改写后的关键词串）
     * @param documents 候选文档文本列表
     * @return 按 relevance_score 降序排列的重排结果；失败时返回 null
     */
    public List<RerankResult> rerank(String query, List<String> documents) {
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            log.debug("重排输入为空，跳过");
            return null;
        }

        String url = config.getEndpoint() + "/rerank";
        log.debug("调用重排API: url={}, query={}, docCount={}", url, query, documents.size());

        try {
            // 构建请求体
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("query", query);
            requestBody.put("documents", documents);
            requestBody.put("top_n", config.getTopK());
            requestBody.put("model", config.getModel());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("重排API返回非成功状态: {}", response.getStatusCode());
                return null;
            }

            // 解析响应
            return parseResponse(response.getBody(), documents);

        } catch (ResourceAccessException e) {
            log.warn("重排API连接失败: {} — 跳过重排", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("重排API调用异常: {} — 跳过重排", e.getClass().getSimpleName());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<RerankResult> parseResponse(Map<String, Object> body, List<String> documents) {
        Object resultsObj = body.get("results");
        if (!(resultsObj instanceof List)) {
            log.warn("重排响应格式异常: results 不是数组");
            return null;
        }

        List<Map<String, Object>> resultsList = (List<Map<String, Object>>) resultsObj;
        List<RerankResult> results = new ArrayList<>();

        double threshold = config.getScoreThreshold();

        for (Map<String, Object> item : resultsList) {
            Object indexObj = item.get("index");
            Object scoreObj = item.get("relevance_score");

            if (!(indexObj instanceof Number) || !(scoreObj instanceof Number)) {
                continue;
            }

            int index = ((Number) indexObj).intValue();
            double score = ((Number) scoreObj).doubleValue();

            // 分数阈值过滤
            if (score < threshold) {
                log.debug("重排结果过滤: index={}, score={} < threshold={}", index, score, threshold);
                continue;
            }

            String text = (index >= 0 && index < documents.size()) ? documents.get(index) : "";
            results.add(new RerankResult(index, score, text));
        }

        // 按分数降序排序
        results.sort(Comparator.comparingDouble(RerankResult::getScore).reversed());

        log.debug("重排完成: {} 个候选 → {} 个通过阈值", documents.size(), results.size());
        return results;
    }

    // ==================== 数据类 ====================

    /** 重排结果 */
    public static class RerankResult {
        private final int index;
        private final double score;
        private final String text;

        public RerankResult(int index, double score, String text) {
            this.index = index;
            this.score = score;
            this.text = text;
        }

        public int getIndex() { return index; }
        public double getScore() { return score; }
        public String getText() { return text; }

        @Override
        public String toString() {
            return "RerankResult{index=" + index + ", score=" + String.format("%.4f", score) + '}';
        }
    }
}
