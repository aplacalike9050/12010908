package com.sustech.privacyaiproject.common.result;

import lombok.Data;

/**
 * 统一 API 响应结果封装
 */
@Data
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "Success", data);
    }

    // 原有的方法保持不变
    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null);
    }

    // 支持自定义状态码的错误方法
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> error(Integer code, String message, T data) {
        return new Result<>(code, message, data);
    }
}