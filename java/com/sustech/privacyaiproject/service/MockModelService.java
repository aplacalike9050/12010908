package com.sustech.privacyaiproject.service;

import com.sustech.privacyaiproject.domain.dto.openai.OpenAiChatCompletionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 实验测试用模型挡板。
 * <p>
 * 开启后只替代最终大模型调用，不影响网关鉴权、S3 检测、Prompt 注入检测、
 * S1/S2 隐私识别脱敏、占位符映射和审计逻辑。
 */
@Slf4j
@Service
public class MockModelService {

    @Value("${gateway.mock-model.enabled:false}")
    private boolean enabled;

    @Value("${gateway.mock-model.response:这是Mock模型响应，当前处于实验测试挡板模式，未调用真实大模型。}")
    private String response;

    @Value("${gateway.mock-model.response-mode:FIXED}")
    private String responseMode;

    @Value("${gateway.mock-model.skip-restore:false}")
    private boolean skipRestore;

    @Value("${gateway.mock-model.stream-chunk-size:8}")
    private int streamChunkSize;

    @Value("${gateway.mock-model.stream-delay-ms:0}")
    private long streamDelayMs;

    /**
     * 判断模型挡板是否启用。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 返回非流式 Mock 响应。
     */
    public String chat(OpenAiChatCompletionRequest request, String sanitizedModelInput) {
        log.debug("模型挡板已启用，跳过真实非流式模型调用，model={}", request == null ? null : request.getModel());
        return resolveResponse(sanitizedModelInput);
    }

    /**
     * 按固定分片返回流式 Mock 响应。
     */
    public StreamingResult stream(OpenAiChatCompletionRequest request, String sanitizedModelInput, Consumer<String> tokenConsumer) {
        log.debug("模型挡板已启用，跳过真实流式模型调用，model={}", request == null ? null : request.getModel());
        String output = resolveResponse(sanitizedModelInput);
        int chunkSize = Math.max(1, streamChunkSize);
        for (int offset = 0; offset < output.length(); offset += chunkSize) {
            int end = Math.min(output.length(), offset + chunkSize);
            String delta = output.substring(offset, end);
            tokenConsumer.accept(delta);
            sleepIfNeeded();
        }
        return new StreamingResult(output);
    }

    /**
     * 实验模式下是否跳过占位符还原。
     */
    public boolean shouldSkipRestore() {
        return enabled && skipRestore;
    }

    private String resolveResponse(String sanitizedModelInput) {
        if ("SANITIZED_INPUT".equalsIgnoreCase(responseMode)) {
            return sanitizedModelInput == null ? "" : sanitizedModelInput;
        }
        return response == null ? "" : response;
    }

    private void sleepIfNeeded() {
        if (streamDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(streamDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mock流式响应被中断", e);
        }
    }

    public record StreamingResult(String text) {
    }
}
