package com.migrationsentinel.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /**
     * Runs review and evaluation jobs. Under the {@code local} transport the dispatcher hands
     * off to this pool once the submitting transaction has committed; the size here is the
     * effective per-instance concurrency. Under {@code kafka}, this pool is unused — the Kafka
     * listener concurrency is the knob and throughput scales with replicas instead.
     */
    @Bean(name = "jobExecutor")
    public Executor jobExecutor(@Value("${sentinel.jobs.pool-size:2}") int poolSize,
                                @Value("${sentinel.jobs.queue-capacity:50}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(Math.max(poolSize, poolSize * 2));
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("sentinel-job-");
        executor.initialize();
        return executor;
    }

    /** Ships committed outbox rows to Kafka off the request/commit thread (kafka transport). */
    @Bean(name = "outboxExecutor")
    public Executor outboxExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("sentinel-outbox-");
        executor.initialize();
        return executor;
    }
}
