package com.sustech.privacyaiproject.service;

import com.sustech.privacyaiproject.common.exception.HighRiskContentException;
import com.sustech.privacyaiproject.common.privacy.PlaceholderGenerator;
import com.sustech.privacyaiproject.domain.entity.Entity;
import com.sustech.privacyaiproject.domain.entity.SanitizeResult;
import com.sustech.privacyaiproject.infrastructure.block.RegexHardBlocker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S1/S2/S3 分级策略引擎。
 * <p>
 * S3: API Key 等极高风险信息，直接拦截；
 * S2: 手机号/身份证/LOC/ORG -> 安全占位符掩码；
 * S1: PER -> 安全占位符掩码，后续可由策略配置切换为合成替换。
 */
@Service
@RequiredArgsConstructor
public class PrivacyPolicyEngine {

    private final PlaceholderGenerator placeholderGenerator;

    /**
     * 对本轮 Prompt 执行分级脱敏策略。
     *
     * @param originalText 原始文本
     * @param regexResult 正则扫描结果
     * @param entities NER 识别结果
     * @param existingMapping 当前会话已有隐私映射
     * @param effectivePolicy 当前请求使用的运行时策略
     * @return 脱敏结果（safePrompt + mapping）
     */
    public SanitizeResult apply(String originalText,
                                RegexHardBlocker.RegexScanResult regexResult,
                                List<Entity> entities,
                                Map<String, String> existingMapping,
                                PrivacyPolicyService.EffectivePrivacyPolicy effectivePolicy) {
        String safePrompt = originalText == null ? "" : originalText;
        PrivacyPolicyService.EffectivePrivacyPolicy policy = effectivePolicy == null
                ? PrivacyPolicyService.EffectivePrivacyPolicy.defaultPolicy()
                : effectivePolicy;
        Map<String, String> mapping = new LinkedHashMap<>();
        if (existingMapping != null && !existingMapping.isEmpty()) {
            mapping.putAll(existingMapping);
        }
        List<Map<String, Object>> findingDetails = new ArrayList<>();
        Map<String, Integer> counters = new ConcurrentHashMap<>();
        initializeCountersFromMapping(mapping, counters);

        // S3：命中 API Key 等极高风险信息，直接拦截
        if (regexResult != null && regexResult.isBlocked()) {
            throw new HighRiskContentException("存在极高安全风险（检测到 API Key/Secret），请求已被拦截");
        }

        // S2：结构化高敏（手机号/身份证）掩码
        if (regexResult != null && regexResult.getFindings() != null) {
            for (RegexHardBlocker.RegexFinding finding : regexResult.getFindings()) {
                String type = normalizeRegexType(finding.getType());
                if (policy.shouldProtect(type)
                        && ("PHONE".equals(type)
                        || "LANDLINE".equals(type)
                        || "ID".equals(type)
                        || "ID_CARD".equals(type)
                        || "PASSPORT".equals(type)
                        || "TRAVEL_PERMIT".equals(type)
                        || "EMAIL".equals(type)
                        || "BANK_CARD".equals(type)
                        || "IPV4".equals(type)
                        || "MAC".equals(type)
                        || "SSN".equals(type))) {
                    String placeholder = ensurePlaceholder(finding.getValue(), type, counters, mapping);
                    safePrompt = safePrompt.replace(finding.getValue(), placeholder);
                    findingDetails.add(findingDetail("REGEX", type, finding.getValue(), placeholder, finding.getStart(), finding.getEnd()));
                }
            }
        }

        // S2 + S1：根据 NER 标签做差异化处理
        List<Entity> safeEntities = entities == null ? new ArrayList<>() : entities;
        for (Entity entity : safeEntities) {
            if (entity == null || entity.getText() == null || entity.getText().isBlank()) {
                continue;
            }
            String type = entity.getType() == null ? "" : entity.getType().toUpperCase();
            String raw = entity.getText();
            if (!isUsableNerEntity(raw, type)) {
                continue;
            }
            if (("LOC".equals(type) || "ORG".equals(type)) && policy.shouldProtect(type)) {
                String placeholder = ensurePlaceholder(raw, type, counters, mapping);
                safePrompt = safePrompt.replace(raw, placeholder);
                findingDetails.add(findingDetail("NER", type, raw, placeholder, -1, -1));
            } else if ("PER".equals(type) && policy.shouldProtect(type)) {
                String placeholder = ensurePlaceholder(raw, type, counters, mapping);
                safePrompt = safePrompt.replace(raw, placeholder);
                findingDetails.add(findingDetail("NER", type, raw, placeholder, -1, -1));
            }
        }

        return SanitizeResult.builder()
                .safePrompt(safePrompt)
                .originalMapping(mapping)
                .findingDetails(findingDetails)
                .hasSensitiveInfo(!mapping.isEmpty())
                .build();
    }

    private String ensurePlaceholder(String raw,
                                     String type,
                                     Map<String, Integer> counters,
                                     Map<String, String> mapping) {
        String existing = findSafeByRaw(mapping, raw);
        if (existing != null) {
            return existing;
        }
        int idx = counters.getOrDefault(type, 0) + 1;
        counters.put(type, idx);
        String placeholder = placeholderGenerator.generate(type, idx);
        mapping.put(placeholder, raw);
        return placeholder;
    }

    private Map<String, Object> findingDetail(String source,
                                              String type,
                                              String value,
                                              String placeholder,
                                              int start,
                                              int end) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", source);
        detail.put("type", type);
        detail.put("riskLevel", riskLevelOf(type));
        detail.put("value", value);
        detail.put("placeholder", placeholder);
        if (start >= 0 && end >= 0) {
            detail.put("start", start);
            detail.put("end", end);
        }
        return detail;
    }

    private String riskLevelOf(String type) {
        return switch (type == null ? "" : type.toUpperCase()) {
            case "PER" -> "S1";
            case "API_KEY", "JWT_TOKEN", "AWS_ACCESS_KEY", "PRIVATE_KEY_BLOCK" -> "S3";
            default -> "S2";
        };
    }

    /**
     * 根据原值查找已有映射，避免重复生成占位符。
     */
    private String findSafeByRaw(Map<String, String> mapping, String raw) {
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            if (raw.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String normalizeRegexType(String type) {
        if (type == null) {
            return "";
        }
        return switch (type.toUpperCase()) {
            case "ID_CARD" -> "ID_CARD";
            case "PHONE", "LANDLINE", "ID", "EMAIL", "API_KEY",
                    "PASSPORT", "TRAVEL_PERMIT", "BANK_CARD", "IPV4", "MAC", "SSN",
                    "JWT_TOKEN", "AWS_ACCESS_KEY", "PRIVATE_KEY_BLOCK" -> type.toUpperCase();
            default -> type.toUpperCase();
        };
    }

    /**
     * 根据历史映射恢复各类型计数器，确保多轮占位符编号连续。
     */
    private void initializeCountersFromMapping(Map<String, String> mapping, Map<String, Integer> counters) {
        for (String safeValue : mapping.keySet()) {
            if (!placeholderGenerator.isPlaceholder(safeValue)) {
                continue;
            }
            String type = placeholderGenerator.extractType(safeValue);
            int idx = placeholderGenerator.extractSequence(safeValue);
            if (type.isBlank() || idx < 0) {
                continue;
            }
            int current = counters.getOrDefault(type, 0);
            if (idx > current) {
                counters.put(type, idx);
            }
        }
    }

    /**
     * 过滤低质量 NER 命中，避免单字/纯符号实体污染映射并干扰后续还原。
     */
    private boolean isUsableNerEntity(String raw, String type) {
        if (raw == null) {
            return false;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return false;
        }
        // 忽略网关生成的占位符本身，防止重复处理
        if (placeholderGenerator.isPlaceholder(text)) {
            return false;
        }
        // 忽略纯标点
        if (text.matches("^[\\p{Punct}，。！？；：“”‘’（）【】《》、\\s]+$")) {
            return false;
        }
        return true;
    }

}
