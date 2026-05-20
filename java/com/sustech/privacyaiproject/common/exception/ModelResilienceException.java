package com.sustech.privacyaiproject.common.exception;

import lombok.Getter;

import java.util.Map;

/**
 * 模型调用稳定性保护异常。
 * <p>
 * 用于封装限流、并发隔离、超时、重试和熔断等 Resilience4j 保护层抛出的异常。
 */
@Getter
public class ModelResilienceException extends BizException {

    private final transient Map<String, Object> detail;

    /**
     * 创建模型稳定性异常。
     *
     * @param code HTTP 风格错误码
     * @param message 错误提示
     * @param detail 稳定性保护明细
     */
    public ModelResilienceException(Integer code, String message, Map<String, Object> detail) {
        super(code, message);
        this.detail = detail == null ? Map.of() : detail;
    }
}
