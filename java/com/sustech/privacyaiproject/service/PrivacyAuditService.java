package com.sustech.privacyaiproject.service;

import com.sustech.privacyaiproject.common.auth.GatewayAuthContext;
import com.sustech.privacyaiproject.common.exception.BizException;
import com.sustech.privacyaiproject.common.exception.HighRiskContentException;
import com.sustech.privacyaiproject.common.exception.ModelResilienceException;
import com.sustech.privacyaiproject.common.exception.PromptInjectionDetectedException;
import com.sustech.privacyaiproject.common.util.SnowflakeIdGenerator;
import com.sustech.privacyaiproject.domain.dto.openai.OpenAiChatCompletionRequest;
import com.sustech.privacyaiproject.domain.dto.openai.OpenAiChatMessage;
import com.sustech.privacyaiproject.domain.dto.security.PromptInjectionDetectionResult;
import com.sustech.privacyaiproject.domain.entity.PrivacyAuditEventEntity;
import com.sustech.privacyaiproject.repository.PrivacyPolicyProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 隐私审计服务。
 * <p>
 * 负责把 OpenAI 兼容调用转换为结构化审计事件，并投递给异步写入器。
 */
@Service
@RequiredArgsConstructor
public class PrivacyAuditService {

    private static final int DETAIL_RETENTION_DAYS = 7;

    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final PrivacyAuditWriter privacyAuditWriter;
    private final ModelResilienceService modelResilienceService;
    private final PrivacyPolicyProfileRepository policyProfileRepository;

    /**
     * 记录 OpenAI 兼容接口成功调用。
     *
     * @param authContext 网关客户端认证上下文
     * @param request OpenAI 请求体
     * @param responseId OpenAI 响应 ID
     * @param responseContent 还原后的响应文本
     * @param latencyMs 调用耗时
     */
    public void recordOpenAiSuccess(GatewayAuthContext authContext,
                                    OpenAiChatCompletionRequest request,
                                    String responseId,
                                    String responseContent,
                                    long latencyMs,
                                    List<PromptInjectionDetectionResult> promptInjectionDetections,
                                    String privacyRiskLevel,
                                    List<Map<String, Object>> findingDetails) {
        PrivacyAuditEventEntity event = baseEvent(authContext, request, responseId, latencyMs);
        event.setSuccess(true);
        event.setStatusCode(200);
        event.setPrivacyRiskLevel(privacyRiskLevel);
        event.setRiskLevel(legacyRiskLevel(promptInjectionDetections, privacyRiskLevel));
        event.setFindingDetailJson(findingDetailJson(findingDetails));
        event.setFindingCount(findingDetails == null ? 0 : findingDetails.size());
        event.setResponseHash(hash(responseContent));
        event.setResponseDetailJson(Map.of("content", safeString(responseContent)));
        applyPromptInjectionFindings(event, promptInjectionDetections);
        event.setResilienceDetailJson(modelResilienceService.snapshot());
        privacyAuditWriter.saveAsync(event);
    }

    /**
     * 记录 OpenAI 兼容接口失败调用。
     *
     * @param authContext 网关客户端认证上下文，认证失败时可为空
     * @param request OpenAI 请求体
     * @param responseId OpenAI 响应 ID
     * @param error 异常对象
     * @param latencyMs 调用耗时
     */
    public void recordOpenAiFailure(GatewayAuthContext authContext,
                                    OpenAiChatCompletionRequest request,
                                    String responseId,
                                    Throwable error,
                                    long latencyMs) {
        PrivacyAuditEventEntity event = baseEvent(authContext, request, responseId, latencyMs);
        event.setSuccess(false);
        event.setStatusCode(resolveStatusCode(error));
        event.setErrorCode(error == null ? "UNKNOWN" : error.getClass().getSimpleName());
        event.setErrorMessage(truncate(error == null ? "未知错误" : error.getMessage(), 512));
        event.setBlocked(isBlocked(error));
        event.setBlockReason(event.getBlocked() ? event.getErrorCode() : null);
        if (error instanceof PromptInjectionDetectedException promptInjectionException) {
            event.setPromptInjectionDetected(true);
            event.setRiskLevel("PROMPT_INJECTION");
            applyPromptInjectionFindings(event, List.of(promptInjectionException.getDetectionResult()));
        }
        if (error instanceof HighRiskContentException highRiskContentException) {
            event.setPrivacyRiskLevel("S3");
            event.setRiskLevel("S3");
            event.setFindingDetailJson(findingDetailJson(highRiskContentException.getFindingDetails()));
            event.setFindingCount(highRiskContentException.getFindingDetails().size());
        }
        if (error instanceof ModelResilienceException modelResilienceException) {
            event.setResilienceDetailJson(modelResilienceException.getDetail());
        } else {
            event.setResilienceDetailJson(modelResilienceService.snapshot());
        }
        privacyAuditWriter.saveAsync(event);
    }

    /**
     * 构建审计事件基础字段。
     */
    private PrivacyAuditEventEntity baseEvent(GatewayAuthContext authContext,
                                             OpenAiChatCompletionRequest request,
                                             String responseId,
                                             long latencyMs) {
        PrivacyAuditEventEntity event = new PrivacyAuditEventEntity();
        event.setId(snowflakeIdGenerator.nextId());
        event.setRequestId(metadataValue(request, "request_id"));
        event.setOpenAiRequestId(responseId);
        event.setLatencyMs(Math.max(latencyMs, 0L));
        event.setCredentialMode(request == null || request.getCredentialMode() == null
                ? "SYSTEM_DEFAULT"
                : request.getCredentialMode());
        event.setModelName(request == null ? null : request.getModel());
        event.setPolicyProfileId(resolveExistingPolicyProfileId(authContext, request));
        event.setMetadataJson(request == null || request.getMetadata() == null ? Map.of() : new LinkedHashMap<>(request.getMetadata()));
        event.setRequestDetailJson(buildRequestDetail(request));
        event.setRequestHash(hash(String.valueOf(event.getRequestDetailJson())));
        event.setDetailExpireAt(LocalDateTime.now().plusDays(DETAIL_RETENTION_DAYS));
        event.setCreateTime(LocalDateTime.now());
        if (authContext != null) {
            event.setGatewayClientId(authContext.gatewayClientId());
            event.setClientId(authContext.clientId());
        }
        return event;
    }

    private Long resolveExistingPolicyProfileId(GatewayAuthContext authContext, OpenAiChatCompletionRequest request) {
        Long requestedPolicyId = request == null ? null : request.getPrivacyPolicyId();
        Long policyProfileId = requestedPolicyId != null ? requestedPolicyId : (authContext == null ? null : authContext.defaultPolicyProfileId());
        if (policyProfileId == null || policyProfileId <= 0) {
            return null;
        }
        return policyProfileRepository.findById(policyProfileId)
                .filter(profile -> authContext == null || authContext.gatewayClientId().equals(profile.getGatewayClientId()))
                .map(profile -> policyProfileId)
                .orElse(null);
    }

    /**
     * 构建短期保留的请求明细 JSON。
     */
    private Map<String, Object> buildRequestDetail(OpenAiChatCompletionRequest request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("model", request.getModel());
        detail.put("stream", Boolean.TRUE.equals(request.getStream()));
        detail.put("credentialMode", request.getCredentialMode());
        detail.put("privacyPolicyId", request.getPrivacyPolicyId());
        detail.put("messageCount", request.getMessages() == null ? 0 : request.getMessages().size());
        detail.put("messages", summarizeMessages(request.getMessages()));
        return detail;
    }

    /**
     * 将 messages 转换为可审计展示的结构。
     */
    private List<Map<String, Object>> summarizeMessages(List<OpenAiChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .map(message -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("role", message.getRole());
                    item.put("content", message.getContent());
                    item.put("contentLength", message.getContent() == null ? 0 : message.getContent().length());
                    return item;
                })
                .toList();
    }

    private String metadataValue(OpenAiChatCompletionRequest request, String key) {
        if (request == null || request.getMetadata() == null) {
            return null;
        }
        Object value = request.getMetadata().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private int resolveStatusCode(Throwable error) {
        if (error instanceof BizException bizException) {
            return bizException.getCode();
        }
        return 500;
    }

    private boolean isBlocked(Throwable error) {
        if (error instanceof HighRiskContentException || error instanceof PromptInjectionDetectedException) {
            return true;
        }
        if (error instanceof BizException bizException) {
            return bizException.getCode() != null && (bizException.getCode() == 400 || bizException.getCode() == 422);
        }
        return false;
    }

    private void applyPromptInjectionFindings(PrivacyAuditEventEntity event,
                                              List<PromptInjectionDetectionResult> detections) {
        if (detections == null || detections.isEmpty()) {
            return;
        }
        event.setPromptInjectionDetected(true);
        event.setRiskLevel(legacyRiskLevel(detections, event.getPrivacyRiskLevel()));
        event.setFindingCount((event.getFindingCount() == null ? 0 : event.getFindingCount()) + detections.size());
        double maxScore = detections.stream()
                .mapToDouble(PromptInjectionDetectionResult::getScore)
                .max()
                .orElse(0.0D);
        event.setPromptInjectionScore(java.math.BigDecimal.valueOf(maxScore));
        event.setPromptInjectionDetailJson(Map.of(
                "action", Boolean.TRUE.equals(event.getBlocked()) ? "BLOCK" : "RECORD",
                "findings", detections.stream()
                        .map(this::promptInjectionView)
                        .toList()
        ));
    }

    private Map<String, Object> promptInjectionView(PromptInjectionDetectionResult result) {
        return Map.of(
                "source", result.getSource(),
                "label", result.getLabel(),
                "reason", result.getReason(),
                "score", result.getScore()
        );
    }

    private String legacyRiskLevel(List<PromptInjectionDetectionResult> promptInjectionDetections, String privacyRiskLevel) {
        if (promptInjectionDetections != null && !promptInjectionDetections.isEmpty()) {
            return "PROMPT_INJECTION";
        }
        return privacyRiskLevel;
    }

    private Map<String, Object> findingDetailJson(List<Map<String, Object>> findingDetails) {
        List<Map<String, Object>> details = findingDetails == null ? List.of() : findingDetails;
        return Map.of(
                "count", details.size(),
                "fields", details
        );
    }

    private String hash(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder("sha256:");
            for (byte b : bytes) {
                output.append(String.format("%02x", b));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256算法不可用", e);
        }
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
