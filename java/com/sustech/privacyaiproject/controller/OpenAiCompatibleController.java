package com.sustech.privacyaiproject.controller;

import com.sustech.privacyaiproject.common.auth.GatewayAuthContext;
import com.sustech.privacyaiproject.domain.dto.openai.OpenAiChatCompletionChunk;
import com.sustech.privacyaiproject.domain.dto.openai.OpenAiChatCompletionRequest;
import com.sustech.privacyaiproject.domain.dto.openai.OpenAiChatCompletionResponse;
import com.sustech.privacyaiproject.domain.dto.openai.OpenAiChatMessage;
import com.sustech.privacyaiproject.service.GatewayClientService;
import com.sustech.privacyaiproject.service.PrivacyAuditService;
import com.sustech.privacyaiproject.service.PrivacyService;
import com.sustech.privacyaiproject.service.StreamRestoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI API 协议兼容控制器。
 * <p>
 * 对外提供标准 /v1/chat/completions 接口，调用方可以按 OpenAI Chat Completions 协议接入隐私网关。
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class OpenAiCompatibleController {

    private final GatewayClientService gatewayClientService;
    private final PrivacyService privacyService;
    private final StreamRestoreService streamRestoreService;
    private final PrivacyAuditService privacyAuditService;

    /**
     * OpenAI 兼容聊天补全接口。
     * <p>
     * 当 stream=true 时返回 SSE；否则返回标准非流式 JSON 响应。
     *
     * @param authorization Authorization 请求头，格式为 Bearer 网关APIKey
     * @param request OpenAI 兼容请求体
     * @return 非流式响应对象或流式 SseEmitter
     */
    @PostMapping(value = "/chat/completions")
    public Object chatCompletions(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestBody OpenAiChatCompletionRequest request) {
        long start = System.currentTimeMillis();
        String responseId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        GatewayAuthContext authContext = null;
        try {
            ensureRequestMetadata(request, responseId);
            authContext = gatewayClientService.authenticate(parseApiKey(authorization));
            if (Boolean.TRUE.equals(request.getStream())) {
                return streamChatCompletions(authContext, request, responseId, start);
            }
            PrivacyService.OpenAiProcessResult result = privacyService.processOpenAiChat(authContext, request);
            OpenAiChatCompletionResponse response = buildCompletionResponse(responseId, request.getModel(), result.content());
            privacyAuditService.recordOpenAiSuccess(authContext, request, responseId, result.content(), elapsed(start),
                    result.promptInjectionDetections(), result.privacyRiskLevel(), result.findingDetails());
            return response;
        } catch (Exception ex) {
            privacyAuditService.recordOpenAiFailure(authContext, request, responseId, ex, elapsed(start));
            throw ex;
        }
    }

    /**
     * 构建 OpenAI 兼容流式响应。
     */
    private SseEmitter streamChatCompletions(GatewayAuthContext authContext,
                                             OpenAiChatCompletionRequest request,
                                             String responseId,
                                             long start) {
        SseEmitter emitter = new SseEmitter(120000L);
        AtomicBoolean clientClosed = new AtomicBoolean(false);
        String mappingKey = privacyService.resolveOpenAiMappingKey(authContext, request);
        emitter.onTimeout(() -> clientClosed.set(true));
        emitter.onError(error -> clientClosed.set(true));
        emitter.onCompletion(() -> clientClosed.set(true));

        CompletableFuture.runAsync(() -> {
            try {
                sendChunk(emitter, buildRoleChunk(responseId, request.getModel()));
                PrivacyService.OpenAiProcessResult result = privacyService.processOpenAiChatStream(authContext, request, delta -> {
                    if (clientClosed.get()) {
                        throw new IllegalStateException("客户端已中断连接");
                    }
                    String restoredDelta = streamRestoreService.feed(mappingKey, delta);
                    if (restoredDelta == null || restoredDelta.isEmpty()) {
                        return;
                    }
                    sendChunk(emitter, buildContentChunk(responseId, request.getModel(), restoredDelta));
                });
                String tail = streamRestoreService.flush(mappingKey);
                if (tail != null && !tail.isEmpty()) {
                    sendChunk(emitter, buildContentChunk(responseId, request.getModel(), tail));
                }
                privacyAuditService.recordOpenAiSuccess(authContext, request, responseId, "STREAM_RESPONSE", elapsed(start),
                        result.promptInjectionDetections(), result.privacyRiskLevel(), result.findingDetails());
                sendChunk(emitter, buildDoneChunk(responseId, request.getModel()));
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (Exception ex) {
                log.warn("OpenAI兼容流式请求失败: {}", ex.getMessage());
                privacyAuditService.recordOpenAiFailure(authContext, request, responseId, ex, elapsed(start));
                streamRestoreService.clear(mappingKey);
                if (!clientClosed.get()) {
                    try {
                        emitter.send(SseEmitter.event().data(Map.of("error", Map.of("message", ex.getMessage()))));
                    } catch (IOException ignored) {
                    }
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    /**
     * 发送单个 OpenAI 流式响应块。
     */
    private void sendChunk(SseEmitter emitter, OpenAiChatCompletionChunk chunk) {
        try {
            emitter.send(SseEmitter.event().data(chunk, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 构建非流式响应体。
     */
    private OpenAiChatCompletionResponse buildCompletionResponse(String responseId, String model, String content) {
        OpenAiChatMessage message = new OpenAiChatMessage();
        message.setRole("assistant");
        message.setContent(content);
        return OpenAiChatCompletionResponse.builder()
                .id(responseId)
                .object("chat.completion")
                .created(Instant.now().getEpochSecond())
                .model(normalizeResponseModel(model))
                .choices(List.of(OpenAiChatCompletionResponse.Choice.builder()
                        .index(0)
                        .message(message)
                        .finishReason("stop")
                        .build()))
                .usage(OpenAiChatCompletionResponse.Usage.builder()
                        .promptTokens(0)
                        .completionTokens(0)
                        .totalTokens(0)
                        .build())
                .build();
    }

    /**
     * 构建流式首包，声明 assistant 角色。
     */
    private OpenAiChatCompletionChunk buildRoleChunk(String responseId, String model) {
        return buildChunk(responseId, model, "assistant", null, null);
    }

    /**
     * 构建流式内容增量包。
     */
    private OpenAiChatCompletionChunk buildContentChunk(String responseId, String model, String content) {
        return buildChunk(responseId, model, null, content, null);
    }

    /**
     * 构建流式结束包。
     */
    private OpenAiChatCompletionChunk buildDoneChunk(String responseId, String model) {
        return buildChunk(responseId, model, null, null, "stop");
    }

    /**
     * 构建通用流式响应块。
     */
    private OpenAiChatCompletionChunk buildChunk(String responseId,
                                                String model,
                                                String role,
                                                String content,
                                                String finishReason) {
        OpenAiChatCompletionChunk.Delta delta = OpenAiChatCompletionChunk.Delta.builder()
                .role(role)
                .content(content)
                .build();
        return OpenAiChatCompletionChunk.builder()
                .id(responseId)
                .object("chat.completion.chunk")
                .created(Instant.now().getEpochSecond())
                .model(normalizeResponseModel(model))
                .choices(List.of(OpenAiChatCompletionChunk.Choice.builder()
                        .index(0)
                        .delta(delta)
                        .finishReason(finishReason)
                        .build()))
                .build();
    }

    /**
     * 确保请求 metadata 中存在 request_id，便于流式还原和审计共用同一个映射 key。
     */
    private void ensureRequestMetadata(OpenAiChatCompletionRequest request, String responseId) {
        if (request.getMetadata() == null) {
            request.setMetadata(new HashMap<>());
        } else if (!(request.getMetadata() instanceof HashMap)) {
            request.setMetadata(new HashMap<>(request.getMetadata()));
        }
        request.getMetadata().putIfAbsent("request_id", responseId);
    }

    /**
     * 从 Authorization 请求头解析 Bearer API Key。
     */
    private String parseApiKey(String header) {
        if (header == null || header.trim().isEmpty()) {
            return "";
        }
        String raw = header.trim();
        if (raw.toLowerCase().startsWith("bearer ")) {
            return raw.substring(7).trim();
        }
        return raw;
    }

    /**
     * 规范化响应中的模型名称。
     */
    private String normalizeResponseModel(String model) {
        if (model == null || model.isBlank()) {
            return "gemini";
        }
        return model.trim();
    }

    /**
     * 计算当前请求耗时。
     */
    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
