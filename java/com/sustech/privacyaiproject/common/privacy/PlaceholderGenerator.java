package com.sustech.privacyaiproject.common.privacy;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 隐私占位符生成器。
 * <p>
 * 统一生成 [PII_{TYPE}_{HASH}_{SEQ}] 格式占位符，避免旧版尖括号占位符与模型保留符或用户自然输入冲突。
 */
@Component
public class PlaceholderGenerator {

    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("^\\[PII_([A-Z0-9_]+)_([a-f0-9]{4,})_([0-9]+)]$");
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 生成新的隐私占位符。
     *
     * @param type 隐私类型，例如 PER、PHONE、ID_CARD
     * @param sequence 同类型序号
     * @return 安全占位符
     */
    public String generate(String type, int sequence) {
        String normalizedType = normalizeType(type);
        return "[PII_" + normalizedType + "_" + randomHash() + "_" + sequence + "]";
    }

    /**
     * 判断文本是否是网关保留的隐私占位符格式。
     *
     * @param value 待判断文本
     * @return 是否匹配保留占位符格式
     */
    public boolean isPlaceholder(String value) {
        return value != null && PLACEHOLDER_PATTERN.matcher(value).matches();
    }

    /**
     * 从占位符中解析隐私类型。
     *
     * @param placeholder 占位符文本
     * @return 隐私类型，解析失败时返回空字符串
     */
    public String extractType(String placeholder) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(placeholder == null ? "" : placeholder);
        return matcher.matches() ? matcher.group(1) : "";
    }

    /**
     * 从占位符中解析序号。
     *
     * @param placeholder 占位符文本
     * @return 序号，解析失败时返回 -1
     */
    public int extractSequence(String placeholder) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(placeholder == null ? "" : placeholder);
        if (!matcher.matches()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(3));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * 规范化隐私类型，保证占位符中只出现大写字母、数字和下划线。
     */
    private String normalizeType(String type) {
        String normalized = type == null ? "UNKNOWN" : type.trim().toUpperCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^A-Z0-9_]", "_");
        return normalized.isBlank() ? "UNKNOWN" : normalized;
    }

    /**
     * 生成短随机哈希片段，用于降低用户猜测和跨请求碰撞风险。
     */
    private String randomHash() {
        byte[] bytes = new byte[2];
        secureRandom.nextBytes(bytes);
        char[] output = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            output[i * 2] = HEX[value >>> 4];
            output[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(output);
    }
}
