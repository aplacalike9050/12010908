package com.sustech.privacyaiproject.common.exception;
//业务异常分层与 HTTP 状态码对齐。

import com.sustech.privacyaiproject.common.result.Result;
import com.sustech.privacyaiproject.domain.dto.security.PromptInjectionDetectionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器
 * 拦截所有 Controller 的异常
 */
@Slf4j // 使用 Slf4j 进行日志记录
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 高危内容拦截异常（S3）。
     * 使用更明确的 message 返回给前端，便于展示风险提示。
     */
    @ExceptionHandler(HighRiskContentException.class)
    public Result<String> handleHighRiskException(HighRiskContentException e) {
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * Prompt 注入拦截异常。
     * <p>
     * 返回检测来源、规则标签、风险分和原因，便于控制台解释“被哪一层拦截”。
     */
    @ExceptionHandler(PromptInjectionDetectedException.class)
    public Result<Map<String, Object>> handlePromptInjectionException(PromptInjectionDetectedException e) {
        PromptInjectionDetectionResult result = e.getDetectionResult();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", result == null ? "" : result.getSource());
        detail.put("label", result == null ? "" : result.getLabel());
        detail.put("score", result == null ? 0D : result.getScore());
        detail.put("reason", result == null ? "" : result.getReason());
        return Result.error(e.getCode(), e.getMessage(), detail);
    }

    @ExceptionHandler(BizException.class)
    public Result<String> handleBizException(BizException e) {
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理所有未知的运行时异常
     */
    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        log.error("系统内部异常: ", e); // 日志
        return Result.error("系统繁忙，请稍后重试"); // 对外隐藏具体堆栈，保障安全
    }

    // 扩展自定义业务异常
}