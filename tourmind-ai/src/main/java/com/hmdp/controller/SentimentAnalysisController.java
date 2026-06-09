package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.ISentimentAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 情感分析 Controller
 */
@RestController
@RequestMapping("/ai/sentiment")
@RequiredArgsConstructor
public class SentimentAnalysisController {

    private final ISentimentAnalysisService sentimentAnalysisService;

    /**
     * 分析单条评论情感
     * POST /ai/sentiment/analyze
     */
    @PostMapping("/analyze")
    public Result analyzeSentiment(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        if (content == null || content.isBlank()) {
            return Result.fail("评论内容不能为空");
        }
        ISentimentAnalysisService.Sentiment sentiment = sentimentAnalysisService.analyze(content);
        return Result.ok(Map.of("sentiment", sentiment.name()));
    }

    /**
     * 获取商铺好评率
     * GET /ai/sentiment/spot/{spotId}/rate
     */
    @GetMapping("/spot/{spotId}/rate")
    public Result getSpotPositiveRate(@PathVariable Long spotId) {
        double rate = sentimentAnalysisService.getSpotPositiveRate(spotId);
        return Result.ok(Map.of(
                "spotId", spotId,
                "positiveRate", rate,
                "percentage", String.format("%.1f%%", rate * 100)
        ));
    }

    /**
     * 批量分析商铺评论情感
     * POST /ai/sentiment/spot/{spotId}/analyze-batch
     */
    @PostMapping("/spot/{spotId}/analyze-batch")
    public Result analyzeSpotBatch(@PathVariable Long spotId) {
        sentimentAnalysisService.analyzeBatch(spotId);
        return Result.ok("分析完成");
    }
}
