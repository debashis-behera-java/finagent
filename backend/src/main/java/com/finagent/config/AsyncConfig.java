package com.finagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded async execution for background research runs (Phase 8).
 *
 * <p>Deliberately a local thread pool — no Kafka/RabbitMQ/Redis. The pool is bounded
 * (core/max threads + bounded queue) so bursts of submissions degrade to
 * caller-runs backpressure instead of unbounded thread growth. Every failure path
 * inside the runner maps to a FAILED status row; the uncaught-exception handler
 * below is a logging safety net only.</p>
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    public static final String RESEARCH_EXECUTOR = "researchExecutor";

    private final FinAgentProperties properties;

    public AsyncConfig(FinAgentProperties properties) {
        this.properties = properties;
    }

    @Bean(name = RESEARCH_EXECUTOR)
    public Executor researchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getResearch().getCorePoolSize());
        executor.setMaxPoolSize(properties.getResearch().getMaxPoolSize());
        executor.setQueueCapacity(properties.getResearch().getQueueCapacity());
        executor.setThreadNamePrefix("research-");
        // Saturated queue: run on the submitting thread (backpressure, never drop).
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error("Uncaught async error in {}: {}",
                method.getName(), ex.getMessage(), ex);
    }
}
