package com.hmdp.service;

/**
 * 评论情感分析服务
 */
public interface ISentimentAnalysisService {

    /**
     * 分析单条评论的情感
     * @param content 评论内容
     * @return 情感标签: POSITIVE(好评), NEUTRAL(中评), NEGATIVE(差评)
     */
    Sentiment analyze(String content);

    /**
     * 批量分析商铺下所有博客的情感
     * @param spotId 商铺ID
     */
    void analyzeBatch(Long spotId);

    /**
     * 获取商铺好评率
     * @param spotId 商铺ID
     * @return 好评率 (0.0 ~ 1.0)
     */
    double getSpotPositiveRate(Long spotId);

    /**
     * 情感标签枚举
     */
    enum Sentiment {
        POSITIVE,  // 好评
        NEUTRAL,   // 中评
        NEGATIVE    // 差评
    }
}
