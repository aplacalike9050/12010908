package com.sustech.privacyaiproject.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 审计日志异步线程池配置。
 * <p>
 * 审计写入属于高频但非主响应链路任务，使用独立线程池避免阻塞模型调用返回。
 */
@Configuration
public class AuditAsyncConfig {

    /**
     * 创建审计专用异步执行器。
     *
     * @return 审计写入线程池
     */
    @Bean("auditTaskExecutor")
    public Executor auditTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("privacy-audit-");
        executor.initialize();
        return executor;
    }
}
