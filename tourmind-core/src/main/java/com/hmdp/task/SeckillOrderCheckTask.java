package com.hmdp.task;

import com.hmdp.entity.SeckillMessage;
import com.hmdp.service.IVoucherOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * @author lbq
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillOrderCheckTask {
    private final RedisTemplate<String, String> redisTemplate;
    private final IVoucherOrderService orderService;
    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 每 15 分钟检查一次未完成的订单
     */
    @Scheduled(cron = "0 */15 * * * ?")
    public void checkFailedOrders() {
        String failedSetKey = "seckill:failed:orders";

        // 获取所有未处理的订单ID
        Set<String> orderIds = redisTemplate.opsForSet().members(failedSetKey);
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }

        for (String orderIdStr : orderIds) {
            Long orderId = Long.parseLong(orderIdStr);

            // 获取订单详细信息
            String orderHashKey = "seckill:failed:order:" + orderId;
            Map<Object, Object> orderInfo = redisTemplate.opsForHash().entries(orderHashKey);

            if (orderInfo.isEmpty()) {
                // 如果详细信息不存在，从集合中移除
                redisTemplate.opsForSet().remove(failedSetKey, orderIdStr);
                continue;
            }

            Long userId = Long.parseLong((String) orderInfo.get("userId"));
            Long voucherId = Long.parseLong((String) orderInfo.get("voucherId"));

            // 检查订单是否已入库
            boolean exists = orderService.existsById(orderId);
            if (!exists) {
                // 如果订单不存在，重新发送消息
                SeckillMessage message = new SeckillMessage();
                message.setOrderId(orderId);
                message.setUserId(userId);
                message.setVoucherId(voucherId);

                rocketMQTemplate.asyncSend(
                        "seckill-topic",
                        message,
                        new SendCallback() {
                            @Override
                            public void onSuccess(SendResult sendResult) {
                                // 发送成功，删除相关缓存
                                removeFailedOrder(orderId);
                                log.info("补偿消息发送成功, orderId:{}", orderId);
                            }

                            @Override
                            public void onException(Throwable throwable) {
                                log.error("补偿消息发送失败, orderId:{}", orderId, throwable);
                            }
                        }
                );
            } else {
                // 订单已存在，删除相关缓存
                removeFailedOrder(orderId);
            }
        }
    }
    /**
     * 删除失败订单的缓存
     */
    private void removeFailedOrder(Long orderId) {
        String orderIdStr = orderId.toString();
        String failedSetKey = "seckill:failed:orders";
        String orderHashKey = "seckill:failed:order:" + orderId;

        // 从集合中移除订单ID
        redisTemplate.opsForSet().remove(failedSetKey, orderIdStr);
        // 删除订单详细信息
        redisTemplate.delete(orderHashKey);
    }
}