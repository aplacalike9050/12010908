package com.sustech.privacyaiproject.common.exception;

import lombok.Getter;

/**
 * 统一业务异常。
 * <p>
 * 通过 code 字段携带 HTTP 风格状态码，由全局异常处理器转换为统一响应。
 */
@Getter
public class BizException extends RuntimeException {

    private final Integer code;

    public BizException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 构建 400 参数错误异常。
     */
    public static BizException badRequest(String message) {
        return new BizException(400, message);
    }

    /**
     * 构建 401 未认证异常。
     */
    public static BizException unauthorized(String message) {
        return new BizException(401, message);
    }

    /**
     * 构建 403 无权限异常。
     */
    public static BizException forbidden(String message) {
        return new BizException(403, message);
    }

    /**
     * 构建 404 资源不存在异常。
     */
    public static BizException notFound(String message) {
        return new BizException(404, message);
    }

    /**
     * 构建 409 资源冲突异常。
     */
    public static BizException conflict(String message) {
        return new BizException(409, message);
    }
}
