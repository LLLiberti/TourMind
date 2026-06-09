package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author lbq
 */
@Configuration
@EnableAsync
@Slf4j
public class TreadPoolConfig {
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(10);                      // 核心线程数
        scheduler.setThreadNamePrefix("scheduled-");   // 线程名前缀
        scheduler.setAwaitTerminationSeconds(60);      // 关闭等待时间
        scheduler.setWaitForTasksToCompleteOnShutdown(true); // 等待任务完成
        scheduler.setErrorHandler(throwable -> {        // 异常处理
            log.error("定时任务执行异常", throwable);
        });
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy()); // 拒绝策略
        scheduler.initialize();
        return scheduler;
    }

}
