package com.sustech.privacyaiproject.service;

import com.sustech.privacyaiproject.common.exception.ModelResilienceException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 模型调用稳定性服务。
 * <p>
 * 使用 Resilience4j 为模型调用统一增加限流、并发隔离、超时、重试和熔断保护。
 */
@Slf4j
@Service
public class ModelResilienceService {

    @Value("${gateway.resilience.rate-limit-per-minute:60}")
    private int rateLimitPerMinute;

    @Value("${gateway.resilience.rate-limit-wait-ms:200}")
    private long rateLimitWaitMs;

    @Value("${gateway.resilience.concurrent-calls:5}")
    private int concurrentCalls;

    @Value("${gateway.resilience.bulkhead-wait-ms:500}")
    private long bulkheadWaitMs;

    @Value("${gateway.resilience.retry-max-attempts:2}")
    private int retryMaxAttempts;

    @Value("${gateway.resilience.retry-wait-ms:300}")
    private long retryWaitMs;

    @Value("${gateway.resilience.timeout-ms:30000}")
    private long timeoutMs;

    @Value("${gateway.resilience.circuit-failure-rate-threshold:50}")
    private float circuitFailureRateThreshold;

    @Value("${gateway.resilience.circuit-sliding-window-size:20}")
    private int circuitSlidingWindowSize;

    @Value("${gateway.resilience.circuit-open-state-wait-ms:30000}")
    private long circuitOpenStateWaitMs;

    private RateLimiter rateLimiter;
    private Retry retry;
    private CircuitBreaker circuitBreaker;
    private TimeLimiter timeLimiter;
    private Bulkhead bulkhead;
    private ExecutorService modelExecutor;

    /**
     * 初始化 Resilience4j 组件。
     */
    @PostConstruct
    public void init() {
        this.rateLimiter = RateLimiter.of("llm-rate-limiter", RateLimiterConfig.custom()
                .limitForPeriod(Math.max(1, rateLimitPerMinute))
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofMillis(Math.max(0L, rateLimitWaitMs)))
                .build());
        this.retry = Retry.of("llm-retry", RetryConfig.custom()
                .maxAttempts(Math.max(1, retryMaxAttempts))
                .waitDuration(Duration.ofMillis(Math.max(0L, retryWaitMs)))
                .retryExceptions(Exception.class)
                .build());
        this.circuitBreaker = CircuitBreaker.of("llm-circuit-breaker", CircuitBreakerConfig.custom()
                .failureRateThreshold(circuitFailureRateThreshold)
                .slidingWindowSize(Math.max(2, circuitSlidingWindowSize))
                .permittedNumberOfCallsInHalfOpenState(2)
                .waitDurationInOpenState(Duration.ofMillis(Math.max(1000L, circuitOpenStateWaitMs)))
                .build());
        this.timeLimiter = TimeLimiter.of("llm-time-limiter", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(Math.max(1000L, timeoutMs)))
                .cancelRunningFuture(true)
                .build());
        this.bulkhead = Bulkhead.of("llm-bulkhead", BulkheadConfig.custom()
                .maxConcurrentCalls(Math.max(1, concurrentCalls))
                .maxWaitDuration(Duration.ofMillis(Math.max(0L, bulkheadWaitMs)))
                .build());
        this.modelExecutor = Executors.newFixedThreadPool(Math.max(1, concurrentCalls));
    }

    /**
     * 在稳定性保护下执行模型调用。
     *
     * @param modelName 模型名称，用于异常和审计明细
     * @param callable 实际模型调用
     * @param <T> 模型调用返回类型
     * @return 模型调用结果
     */
    public <T> T execute(String modelName, Callable<T> callable) {
        Callable<T> timeLimited = () -> timeLimiter.executeFutureSupplier(() ->
                CompletableFuture.supplyAsync(() -> callUnchecked(callable), modelExecutor)
        );
        Callable<T> protectedCall = Bulkhead.decorateCallable(bulkhead, timeLimited);
        protectedCall = CircuitBreaker.decorateCallable(circuitBreaker, protectedCall);
        protectedCall = RateLimiter.decorateCallable(rateLimiter, protectedCall);
        protectedCall = Retry.decorateCallable(retry, protectedCall);
        try {
            return protectedCall.call();
        } catch (Exception ex) {
            throw toResilienceException(modelName, ex);
        }
    }

    /**
     * 获取当前稳定性组件状态，供审计和监控使用。
     *
     * @return 稳定性状态快照
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("rateLimiterAvailablePermissions", rateLimiter.getMetrics().getAvailablePermissions());
        output.put("bulkheadAvailableConcurrentCalls", bulkhead.getMetrics().getAvailableConcurrentCalls());
        output.put("circuitBreakerState", circuitBreaker.getState().name());
        output.put("circuitBreakerFailureRate", circuitBreaker.getMetrics().getFailureRate());
        return output;
    }

    /**
     * 应用关闭时释放模型调用线程池。
     */
    @PreDestroy
    public void destroy() {
        if (modelExecutor != null) {
            modelExecutor.shutdownNow();
        }
    }

    /**
     * 将受检异常转为运行时异常，以便 CompletableFuture 传播。
     */
    private <T> T callUnchecked(Callable<T> callable) {
        try {
            return callable.call();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 将 Resilience4j 异常转换为统一业务异常。
     */
    private ModelResilienceException toResilienceException(String modelName, Exception ex) {
        Throwable root = rootCause(ex);
        Map<String, Object> detail = new LinkedHashMap<>(snapshot());
        detail.put("model", modelName);
        detail.put("exceptionType", root.getClass().getName());
        detail.put("exceptionMessage", root.getMessage());
        if (root instanceof io.github.resilience4j.ratelimiter.RequestNotPermitted) {
            return new ModelResilienceException(429, "模型调用触发限流，请稍后重试", detail);
        }
        if (root instanceof io.github.resilience4j.bulkhead.BulkheadFullException) {
            return new ModelResilienceException(429, "模型调用并发已满，请稍后重试", detail);
        }
        if (root instanceof java.util.concurrent.TimeoutException) {
            return new ModelResilienceException(504, "模型调用超时", detail);
        }
        if (root instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
            return new ModelResilienceException(503, "模型调用熔断中，请稍后重试", detail);
        }
        return new ModelResilienceException(502, "模型调用失败: " + root.getMessage(), detail);
    }

    /**
     * 获取根因异常。
     */
    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
