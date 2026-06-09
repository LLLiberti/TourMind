package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 情感分析配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "sentiment")
public class SentimentConfig {
    /**
     * 批量分析大小
     */
    private int batchSize = 100;

    /**
     * 缓存有效期（小时）
     */
    private int cacheTtlHours = 1;
}
