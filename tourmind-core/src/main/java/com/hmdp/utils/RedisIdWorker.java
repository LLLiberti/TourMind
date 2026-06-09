package com.hmdp.utils;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author lbq
 */
@Component
public class RedisIdWorker {

    @Resource
    private RedisTemplate<Object, Object> redisTemplate;

    private static final Long BEGIN_TIMESTAMP = 1640995200L;
    private static final int COUNT_BITS = 32;
    public long nextId(String key) {
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long timestamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        //2.生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = (long) redisTemplate.opsForValue().get("icr:" + key + ":" + date);

        return timestamp << COUNT_BITS | count;

    }
}
