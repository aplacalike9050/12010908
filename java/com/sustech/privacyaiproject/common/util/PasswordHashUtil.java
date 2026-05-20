package com.sustech.privacyaiproject.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 开发者账号密码哈希工具。
 * <p>
 * 当前阶段使用 SHA-256 做基础哈希，后续生产化可替换为 BCrypt/Argon2。
 */
public final class PasswordHashUtil {

    private PasswordHashUtil() {
    }

    public static String hash(String rawPassword) {
        if (rawPassword == null) {
            rawPassword = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawPassword.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("sha256:");
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("密码哈希失败", ex);
        }
    }

    public static boolean matches(String rawPassword, String hashedPassword) {
        return hash(rawPassword).equals(hashedPassword);
    }
}
