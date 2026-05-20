package com.sustech.privacyaiproject.domain.strategy.impl;
//合成替换策略类，处理低敏数据
import com.sustech.privacyaiproject.domain.strategy.PrivacyStrategy;
import com.sustech.privacyaiproject.domain.entity.SanitizeResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 策略 B：合成数据替换策略
 * 将敏感信息替换为虚构的真实数据（如 "张三" -> "王五"）
 * 优点：保持语境通顺，AI 效果好
 * 缺点：还原逻辑复杂
 */
@Component("SYNTHESIS")
public class SynthesisStrategy implements PrivacyStrategy {

    private static final Pattern CHINESE_NAME_PATTERN = Pattern.compile("(?<![\\u4e00-\\u9fa5])([\\u4e00-\\u9fa5]{2,3})(?![\\u4e00-\\u9fa5])");
    private static final String[] NAME_POOL = {"张伟", "王芳", "李娜", "刘洋", "陈杰", "赵敏"};

    /**
     * 合成策略：将可能的人名替换为同属性假名。
     */
    @Override
    public SanitizeResult sanitize(String originalText) {
        String safePrompt = originalText == null ? "" : originalText;
        Map<String, String> mapping = new LinkedHashMap<>();
        Matcher matcher = CHINESE_NAME_PATTERN.matcher(safePrompt);
        StringBuffer sb = new StringBuffer();
        int index = 0;
        while (matcher.find()) {
            String rawName = matcher.group(1);
            String fakeName = NAME_POOL[index % NAME_POOL.length];
            index++;
            mapping.put(fakeName, rawName);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(fakeName));
        }
        matcher.appendTail(sb);

        return SanitizeResult.builder()
                .safePrompt(sb.toString())
                .originalMapping(mapping)
                .hasSensitiveInfo(!mapping.isEmpty())
                .build();
    }

    /**
     * 策略类型标识。
     */
    @Override
    public String getStrategyType() {
        return "SYNTHESIS";
    }
}