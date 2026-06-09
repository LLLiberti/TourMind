package com.hmdp.consumer;

import com.hmdp.entity.SeckillMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

/**
 * @author lbq
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = "seckill-topic",
                        consumerGroup = "seckill-consumer-group",
                        enableMsgTrace = true,
                        customizedTraceTopic = "seckill_trace",
                        consumeMode = ConsumeMode.CONCURRENTLY,
                        maxReconsumeTimes = 3,
                        delayLevelWhenNextConsume = 2)
public class SeckillConsumer implements RocketMQListener<SeckillMessage> {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Override
    @Transactional
    public void onMessage(SeckillMessage seckillMessage) {
        Long orderId = seckillMessage.getOrderId();
        Long userId = seckillMessage.getUserId();
        Long voucherId = seckillMessage.getVoucherId();

        // 幂等性：订单已入库则跳过（处理重试/重复投递）
        if (voucherOrderService.existsById(orderId)) {
            log.info("订单已存在，跳过重复处理, orderId={}", orderId);
            return;
        }

        // 1. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrderService.save(voucherOrder);

        // 2. 同步扣减 MySQL 库存（乐观锁防超卖）
        boolean updated = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();

        if (!updated) {
            throw new RuntimeException("MySQL库存扣减失败, orderId=" + orderId);
        }
    }
}
