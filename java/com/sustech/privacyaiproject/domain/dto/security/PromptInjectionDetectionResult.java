package com.sustech.privacyaiproject.domain.dto.security;

import lombok.Builder;
import lombok.Data;

/**
 * Prompt 注入检测结果。
 * <p>
 * 同时承载启发式规则和模型推理结果，便于审计和后续控制台展示。
 */
@Data
@Builder
public class PromptInjectionDetectionResult {

    /**
     * 是否判定为 Prompt 注入攻击。
     */
    private boolean malicious;

    /**
     * 恶意概率或规则风险分。
     */
    private double score;

    /**
     * 命中的检测层，例如 HEURISTIC_RULE、MODEL。
     */
    private String source;

    /**
     * 命中的标签或规则编码。
     */
    private String label;

    /**
     * 面向日志和审计的原因描述。
     */
    private String reason;

    /**
     * 构建安全结果。
     *
     * @return 安全检测结果
     */
    public static PromptInjectionDetectionResult safe() {
        return PromptInjectionDetectionResult.builder()
                .malicious(false)
                .score(0.0D)
                .source("NONE")
                .label("SAFE")
                .reason("")
                .build();
    }
}
