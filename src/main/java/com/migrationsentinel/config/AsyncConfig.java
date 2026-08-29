package com.migrationsentinel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /**
     * Reviews and evaluation runs are long (they build a Docker container and call an LLM),
     * so controllers hand off to this pool and return a job id immediately.
     */
    @Bean(name = "reviewExecutor")
    public Executor reviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("sentinel-review-");
        executor.initialize();
        return executor;
    }
}
