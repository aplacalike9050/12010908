package com.sustech.privacyaiproject.domain.enums;

/**
 * 模型密钥使用模式。
 * <p>
 * SYSTEM_DEFAULT 表示使用网关服务端配置的默认模型密钥；
 * CLIENT_PROVIDED 表示使用开发者在控制台绑定的自定义模型密钥。
 */
public enum CredentialMode {
    SYSTEM_DEFAULT,
    CLIENT_PROVIDED;

    /**
     * 将请求中的字符串转换为密钥模式，空值默认使用系统密钥。
     *
     * @param value 请求传入的密钥模式
     * @return 标准密钥模式枚举
     */
    public static CredentialMode from(String value) {
        if (value == null || value.isBlank()) {
            return SYSTEM_DEFAULT;
        }
        for (CredentialMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return SYSTEM_DEFAULT;
    }
}
