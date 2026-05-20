package com.sustech.privacyaiproject.domain.strategy.impl;
//掩码策略类，处理高敏数据
import com.sustech.privacyaiproject.domain.strategy.PrivacyStrategy;
import com.sustech.privacyaiproject.domain.entity.SanitizeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component("MASK")
public class MaskingStrategy implements PrivacyStrategy {

    /** 手机号正则 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    /** 身份证正则 */
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)(\\d{15}|\\d{17}[0-9Xx])(?!\\d)");
    /** 邮箱正则 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * 掩码策略：将结构化高敏信息替换成占位符。
     */
    @Override
    public SanitizeResult sanitize(String originalText) {
        String safePrompt = originalText == null ? "" : originalText;
        Map<String, String> mapping = new LinkedHashMap<>();

        safePrompt = maskByPattern(safePrompt, PHONE_PATTERN, "PHONE", mapping);
        safePrompt = maskByPattern(safePrompt, ID_CARD_PATTERN, "ID", mapping);
        safePrompt = maskByPattern(safePrompt, EMAIL_PATTERN, "EMAIL", mapping);

        return SanitizeResult.builder()
                .safePrompt(safePrompt)
                .originalMapping(mapping)
                .hasSensitiveInfo(!mapping.isEmpty())
                .build();
    }

    /**
     * 策略类型标识。
     */
    @Override
    public String getStrategyType() {
        return "MASK";
    }

    /**
     * 按正则执行占位符替换并记录映射。
     */
    private String maskByPattern(String text, Pattern pattern, String type, Map<String, String> mapping) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        int counter = 1;
        while (matcher.find()) {
            String raw = matcher.group();
            String placeholder = "<" + type + "_" + counter++ + ">";
            mapping.put(placeholder, raw);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}