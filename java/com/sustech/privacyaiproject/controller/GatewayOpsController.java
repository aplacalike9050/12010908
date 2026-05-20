package com.sustech.privacyaiproject.controller;

import com.sustech.privacyaiproject.common.result.Result;
import com.sustech.privacyaiproject.repository.PrivacyAuditEventRepository;
import com.sustech.privacyaiproject.service.ModelResilienceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关运维接口控制器。
 * <p>
 * 提供健康检查和基础调用统计，供 Postman、运维脚本和后续控制台使用。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class GatewayOpsController {

    private final PrivacyAuditEventRepository privacyAuditEventRepository;
    private final ModelResilienceService modelResilienceService;

    /**
     * 网关健康检查接口。
     *
     * @return 网关健康状态
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("service", "privacy-ai-gateway");
        data.put("timestamp", Instant.now().toString());
        data.put("resilience", modelResilienceService.snapshot());
        return Result.success(data);
    }

    /**
     * 网关基础指标汇总接口。
     *
     * @return 审计事件与稳定性指标摘要
     */
    @GetMapping("/metrics/summary")
    public Result<Map<String, Object>> metricsSummary() {
        long total = privacyAuditEventRepository.count();
        long success = privacyAuditEventRepository.countBySuccess(true);
        long blocked = privacyAuditEventRepository.countByBlocked(true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalCalls", total);
        data.put("successCalls", success);
        data.put("failedCalls", Math.max(0, total - success));
        data.put("blockedCalls", blocked);
        data.put("resilience", modelResilienceService.snapshot());
        data.put("timestamp", Instant.now().toString());
        return Result.success(data);
    }
}
