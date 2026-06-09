package com.hmdp.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.SeckillMessage;
import com.hmdp.service.IVoucherOrderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 秒杀事务消息监听器
 * 执行本地事务(Lua脚本扣库存)，并提供 Broker 回查
 *
 * @author lbq
 */
@Component
@Slf4j
@RocketMQTransactionListener(rocketMQTemplateBeanName = "rocketMQTemplate")
public class SeckillTransactionListener implements RocketMQLocalTransactionListener {

    @Resource
    private RedisTemplate<Object, Object> redisTemplate;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ObjectMapper objectMapper;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 执行本地事务 — 调用 Lua 脚本扣减库存
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        SeckillMessage sm = (SeckillMessage) msg.getPayload();
        try {
            Long result = redisTemplate.execute(SECKILL_SCRIPT,
                    Collections.emptyList(),
                    sm.getVoucherId().toString(),
                    sm.getUserId().toString());

            if (result != null && result.intValue() == 0) {
                log.info("本地事务成功, orderId={}", sm.getOrderId());
                return RocketMQLocalTransactionState.COMMIT;
            }
            log.warn("本地事务失败, orderId={}, code={}", sm.getOrderId(), result);
            return RocketMQLocalTransactionState.ROLLBACK;
        } catch (Exception e) {
            log.error("本地事务异常, orderId={}", sm.getOrderId(), e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    /**
     * Broker 回查 — 当 executeLocalTransaction 返回 UNKNOWN 或执行超时时调用
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        SeckillMessage sm = extractSeckillMessage(msg);
        if (sm == null) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        // 1. 订单已入库 → 提交（消费者已完成）
        if (voucherOrderService.existsById(sm.getOrderId())) {
            log.info("回查-订单已入库, orderId={}", sm.getOrderId());
            return RocketMQLocalTransactionState.COMMIT;
        }

        // 2. Redis 有秒杀标记 → 提交（Lua脚本已执行，等待消费者入库）
        Boolean isMember = redisTemplate.opsForSet()
                .isMember("seckill:order:" + sm.getVoucherId(), sm.getUserId());
        if (Boolean.TRUE.equals(isMember)) {
            log.info("回查-Lua已执行, orderId={}", sm.getOrderId());
            return RocketMQLocalTransactionState.COMMIT;
        }

        // 3. DB、Redis 均无记录 → 回滚（本地事务未执行）
        log.info("回查-无记录, 回滚, orderId={}", sm.getOrderId());
        return RocketMQLocalTransactionState.ROLLBACK;
    }

    /**
     * 从 Message 中提取 SeckillMessage。
     * 同一 JVM 会话内 payload 为原对象；重启后回查时 payload 为 byte[]。
     */
    private SeckillMessage extractSeckillMessage(Message msg) {
        Object payload = msg.getPayload();
        if (payload instanceof SeckillMessage sm) {
            return sm;
        }
        if (payload instanceof byte[] bytes) {
            try {
                return objectMapper.readValue(bytes, SeckillMessage.class);
            } catch (Exception e) {
                log.error("回查-反序列化失败", e);
                return null;
            }
        }
        log.error("回查-无法识别的消息类型: {}",
                payload != null ? payload.getClass().getName() : "null");
        return null;
    }
}
