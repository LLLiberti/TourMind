package com.hmdp.consumer;

import com.hmdp.entity.SeckillMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 死信队列消费者 — 监听重试耗尽后被投递到 %DLQ% 的消息
 * <p>
 * 触发条件：消费者 {@link SeckillConsumer} 重试 3 次后仍然失败，
 * RocketMQ 自动将消息投递到 DLQ Topic。
 * 这里记录失败详情，供人工排查与补偿。
 *
 * @author lbq
 */
@Component
@Slf4j
@RocketMQMessageListener(topic = "%DLQ%seckill-consumer-group",
                        consumerGroup = "seckill-dlq-consumer-group",
                        consumeMode = ConsumeMode.CONCURRENTLY)
public class SeckillDlqConsumer implements RocketMQListener<SeckillMessage> {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    private static final String DLQ_FAILED_ORDERS_KEY = "seckill:dlq:orders";

    @Override
    public void onMessage(SeckillMessage seckillMessage) {
        log.warn("收到死信消息：订单重试耗尽进入 DLQ → orderId={}, userId={}, voucherId={}",
                seckillMessage.getOrderId(),
                seckillMessage.getUserId(),
                seckillMessage.getVoucherId());

        // 1. 将失败的订单 ID 存入 Redis Set，便于运维查询
        Long orderId = seckillMessage.getOrderId();
        redisTemplate.opsForSet().add(DLQ_FAILED_ORDERS_KEY, orderId.toString());

        // 2. 将详细失败信息存入 Redis Hash，保留 72 小时
        String detailKey = "seckill:dlq:order:" + orderId;
        Map<String, Object> detail = new HashMap<>();
        detail.put("orderId", orderId.toString());
        detail.put("userId", seckillMessage.getUserId().toString());
        detail.put("voucherId", seckillMessage.getVoucherId().toString());
        detail.put("enterDlqTime", LocalDateTime.now().toString());
        detail.put("reason", "消费者重试 3 次后仍然失败，自动进入死信队列");
        redisTemplate.opsForHash().putAll(detailKey, detail);
        // 72 小时后自动清理
        redisTemplate.expire(detailKey, 72, java.util.concurrent.TimeUnit.HOURS);
    }
}
