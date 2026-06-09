package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 会话管理配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "conversation")
public class ConversationConfig {

    /**
     * 会话超时时间（分钟），默认 30 分钟
     */
    private int timeoutMinutes = 30;

    /**
     * 最大消息数，默认 20 条
     */
    private int maxMessages = 20;

    /**
     * 每个用户最大会话数，默认 5 个
     */
    private int maxConversationsPerUser = 5;
}
