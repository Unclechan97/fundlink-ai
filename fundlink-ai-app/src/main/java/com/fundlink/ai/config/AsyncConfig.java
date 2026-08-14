package com.fundlink.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * @Async 执行器治理（B6.3）：核心 4 / 上限 16 / 队列 64 / CallerRunsPolicy。
 * <p>
 * Bean 名 "taskExecutor" — Spring 的 @Async 未指定名字时默认匹配该 bean，
 * 替代默认的 SimpleAsyncTaskExecutor（每次新建线程、无上限）。
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor(
            @Value("${fundlink.loop.core-pool-size:4}") int corePoolSize,
            @Value("${fundlink.loop.max-pool-size:16}") int maxPoolSize,
            @Value("${fundlink.loop.queue-capacity:64}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("ai-loop-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
