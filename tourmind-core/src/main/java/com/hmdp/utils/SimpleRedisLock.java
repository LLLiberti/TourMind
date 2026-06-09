package com.hmdp.utils;

import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author lbq
 */
public class SimpleRedisLock implements ILock{

    private final String name;
    private final RedisTemplate<Object, Object> redisTemplate;

    public SimpleRedisLock(String name, RedisTemplate<Object, Object> redisTemplate) {
        this.name = name;
        this.redisTemplate = redisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString();
    @Override
    public boolean tryLock(long timeoutSec) {
        String id = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = redisTemplate.opsForValue().
                setIfAbsent(KEY_PREFIX + name, id , timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        String id = ID_PREFIX + Thread.currentThread().getId();
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "return redis.call('del', KEYS[1]) " +
                "else return 0 end";

        redisTemplate.execute((RedisCallback<Long>) connection -> {
            connection.eval(script.getBytes(), ReturnType.INTEGER, 1,
                    (KEY_PREFIX + name).getBytes(), id.getBytes());
            return null;
        });
    }
}
