package com.hmdp.utils;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 全局唯一 ID 生成器（基于 Redis 自增 + 时间戳位运算）
 * <p>
 * ID 结构（64位）：
 * <pre>
 * |-------- 高 32 位 --------|-------- 低 32 位 --------|
 * |     时间戳（距基准秒数）    |     Redis 每日自增序列     |
 * </pre>
 *
 * @author lbq
 */
@Component
public class RedisIdWorker {

    @Resource
    private RedisTemplate<Object, Object> redisTemplate;

    /** 基准时间戳：2022-01-01 00:00:00 UTC */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    /** 序列号占用位数 */
    private static final int COUNT_BITS = 32;

    /** 序列号最大值（约 43 亿/天） */
    private static final long MAX_COUNT = (1L << COUNT_BITS) - 1;

    /**
     * 生成全局唯一 ID
     *
     * @param key 业务标识（如 "order"）
     * @return 64 位唯一 ID
     * @throws IllegalStateException 当日序列号耗尽时抛出
     */
    public long nextId(String key) {
        // 1. 生成时间戳（距基准时间的秒数）
        LocalDateTime now = LocalDateTime.now();
        long timestamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;

        // 2. Redis 原子自增获取当日序列号（key 按天隔离，跨天自动重置）
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = redisTemplate.opsForValue().increment("icr:" + key + ":" + date);

        if (count == null) {
            throw new IllegalStateException("Redis increment failed for key: " + key);
        }

        // 3. 序列号溢出保护
        if (count > MAX_COUNT) {
            throw new IllegalStateException(
                    "ID sequence exhausted for key: " + key + " on " + date);
        }

        // 4. 拼接：高 32 位 = 时间戳，低 32 位 = 序列号
        return (timestamp << COUNT_BITS) | count;
    }
}
