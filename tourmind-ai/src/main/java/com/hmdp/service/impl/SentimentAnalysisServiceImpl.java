package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.config.SentimentConfig;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.ISentimentAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 评论情感分析服务实现
 */
@Slf4j
@Service
public class SentimentAnalysisServiceImpl implements ISentimentAnalysisService {

    private static final String SENTIMENT_PROMPT = """
        请分析以下评论的情感倾向，返回仅包含一个词：好评、中评 或 差评

        评论内容：%s
        """;

    private static final String RATE_KEY_PREFIX = "sentiment:rate:spot:";
    private static final String BLOG_SENTIMENT_KEY_PREFIX = "blog:sentiment:";

    @Resource
    private ChatClient chatClient;

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private BlogCommentsMapper blogCommentsMapper;

    @Resource
    private RedisTemplate<Object, Object> redisTemplate;

    @Resource
    private SentimentConfig sentimentConfig;

    @Override
    public Sentiment analyze(String content) {
        if (content == null || content.isBlank()) {
            return Sentiment.NEUTRAL;
        }

        String response = chatClient.prompt()
                .user(String.format(SENTIMENT_PROMPT, content))
                .call()
                .content();

        return parseSentiment(response);
    }

    @Override
    public void analyzeBatch(Long spotId) {
        // 1. 查询该景点的所有博客
        List<Blog> blogs = blogMapper.selectList(
                new LambdaQueryWrapper<Blog>().eq(Blog::getSpotId, spotId)
        );

        // 2. 分析每条博客内容的情感
        for (Blog blog : blogs) {
            Sentiment sentiment = analyze(blog.getContent());
            // 存储情感结果到 Redis
            updateBlogSentiment(blog.getId(), sentiment);
            log.info("Blog {} sentiment: {}", blog.getId(), sentiment);
        }

        // 3. 清除景点好评率缓存，触发重新计算
        redisTemplate.delete(RATE_KEY_PREFIX + spotId);

        log.info("Batch analysis completed for spot {}, analyzed {} blogs", spotId, blogs.size());
    }

    @Override
    public double getSpotPositiveRate(Long spotId) {
        String cacheKey = RATE_KEY_PREFIX + spotId;

        // 1. 尝试从缓存获取
        Double cachedRate = (Double) redisTemplate.opsForValue().get(cacheKey);
        if (cachedRate != null) {
            return cachedRate;
        }

        // 2. 从数据库统计
        List<Blog> blogs = blogMapper.selectList(
                new LambdaQueryWrapper<Blog>().eq(Blog::getSpotId, spotId)
        );

        if (blogs.isEmpty()) {
            return 0.0;
        }

        long positiveCount = 0;
        for (Blog blog : blogs) {
            String sentimentKey = BLOG_SENTIMENT_KEY_PREFIX + blog.getId();
            Object sentimentObj = redisTemplate.opsForValue().get(sentimentKey);
            if (sentimentObj != null) {
                String sentiment = sentimentObj.toString();
                if ("POSITIVE".equals(sentiment)) {
                    positiveCount++;
                }
            } else {
                // 如果缓存中没有，进行实时分析
                Sentiment sentiment = analyze(blog.getContent());
                updateBlogSentiment(blog.getId(), sentiment);
                if (sentiment == Sentiment.POSITIVE) {
                    positiveCount++;
                }
            }
        }

        double rate = (double) positiveCount / blogs.size();

        // 3. 写入缓存
        redisTemplate.opsForValue().set(cacheKey, rate, sentimentConfig.getCacheTtlHours(), TimeUnit.HOURS);

        return rate;
    }

    private Sentiment parseSentiment(String response) {
        if (response == null) {
            return Sentiment.NEUTRAL;
        }
        String trimmed = response.trim();
        if (trimmed.contains("好评")) {
            return Sentiment.POSITIVE;
        } else if (trimmed.contains("差评")) {
            return Sentiment.NEGATIVE;
        }
        return Sentiment.NEUTRAL;
    }

    private void updateBlogSentiment(Long blogId, Sentiment sentiment) {
        String key = BLOG_SENTIMENT_KEY_PREFIX + blogId;
        redisTemplate.opsForValue().set(key, sentiment.name(), 30, TimeUnit.DAYS);
    }
}
