package com.sustech.privacyaiproject.common.exception;

import com.sustech.privacyaiproject.domain.dto.security.PromptInjectionDetectionResult;
import lombok.Getter;

/**
 * Prompt 注入攻击拦截异常。
 */
@Getter
public class PromptInjectionDetectedException extends BizException {

    private final transient PromptInjectionDetectionResult detectionResult;

    /**
     * 创建 Prompt 注入拦截异常。
     *
     * @param detectionResult 检测结果
     */
    public PromptInjectionDetectedException(PromptInjectionDetectionResult detectionResult) {
        super(400, "检测到疑似Prompt注入攻击，请求已被拦截");
        this.detectionResult = detectionResult;
    }
}
