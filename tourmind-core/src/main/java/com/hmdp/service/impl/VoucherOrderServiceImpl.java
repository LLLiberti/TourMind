package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillMessage;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RedisTemplate<Object, Object> redisTemplate;

    @Resource
    private RocketMQTemplate rocketMQTemplate;


    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //尚未开始
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //已经结束
            return Result.fail("秒杀已经结束");
        }
        if (voucher.getStock() < 1) {
            //库存不足
            return Result.fail("库存不足");
        }
        Long userId = UserContext.getUser().getId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        try {
            if (!lock.tryLock(500, 10000, TimeUnit.MILLISECONDS)) {
                return Result.fail("获取锁失败");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        try {
            //获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId) ;
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }

    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserContext.getUser().getId();
        // 提前生成订单ID
        Long orderId = redisIdWorker.nextId("order");

        SeckillMessage seckillMessage = new SeckillMessage();
        seckillMessage.setVoucherId(voucherId);
        seckillMessage.setUserId(userId);
        seckillMessage.setOrderId(orderId);

        Message<SeckillMessage> message = MessageBuilder
                .withPayload(seckillMessage)
                .build();

        try {
            TransactionSendResult result = rocketMQTemplate.sendMessageInTransaction(
                    "seckill-topic", message, null);

            if (result.getLocalTransactionState() == LocalTransactionState.COMMIT_MESSAGE) {
                return Result.ok(orderId);
            }

            // 回滚 — 根据 Redis 状态返回具体错误原因
            if (result.getLocalTransactionState() == LocalTransactionState.ROLLBACK_MESSAGE) {
                Object stockObj = redisTemplate.opsForValue()
                        .get("seckill:stock:" + voucherId);
                if (stockObj != null && Integer.parseInt(stockObj.toString()) <= 0) {
                    return Result.fail("库存不足");
                }
                Boolean isMember = redisTemplate.opsForSet()
                        .isMember("seckill:order:" + voucherId, userId);
                if (Boolean.TRUE.equals(isMember)) {
                    return Result.fail("不能重复下单");
                }
                return Result.fail("秒杀失败");
            }

            // UNKNOW — Broker 稍后触发 checkLocalTransaction 回查
            log.warn("事务消息状态未知(UNKNOW), orderId={}", orderId);
            return Result.ok(orderId);
        } catch (Exception e) {
            log.error("事务消息发送失败, orderId={}", orderId, e);
            return Result.fail("系统繁忙，请稍后重试");
        }
    }

    @Override
    public boolean existsById(Long orderId) {
        if(orderId == null){
            return false;
        }
        return baseMapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getId, orderId)) > 0;
    }
}
