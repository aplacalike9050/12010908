package com.sustech.privacyaiproject.service;

import com.sustech.privacyaiproject.domain.entity.PrivacyPolicyRuleEntity;
import com.sustech.privacyaiproject.repository.PrivacyPolicyRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 隐私策略运行时服务。
 * <p>
 * 负责把数据库中的策略规则转换为策略引擎可直接判断的运行时配置。
 */
@Service
@RequiredArgsConstructor
public class PrivacyPolicyService {

    private static final Set<String> FORCE_BLOCK_TYPES = Set.of(
            "API_KEY", "JWT_TOKEN", "AWS_ACCESS_KEY", "PRIVATE_KEY_BLOCK"
    );

    public static final String PROMPT_INJECTION_BLACKLIST = "PROMPT_INJECTION_BLACKLIST";
    public static final String PROMPT_INJECTION_MODEL = "PROMPT_INJECTION_MODEL";

    private final PrivacyPolicyRuleRepository privacyPolicyRuleRepository;

    /**
     * 加载指定策略档案的运行时策略。
     * <p>
     * 当策略档案为空或没有配置规则时，默认保护所有 S1/S2 类型；S3 密钥类始终强制拦截。
     *
     * @param policyProfileId 策略档案主键，可为空
     * @return 运行时策略
     */
    public EffectivePrivacyPolicy loadEffectivePolicy(Long policyProfileId) {
        if (policyProfileId == null) {
            return EffectivePrivacyPolicy.defaultPolicy();
        }
        List<PrivacyPolicyRuleEntity> rules = privacyPolicyRuleRepository.findByPolicyProfileId(policyProfileId);
        Map<String, PrivacyPolicyRuleEntity> ruleMap = new HashMap<>();
        for (PrivacyPolicyRuleEntity rule : rules) {
            if (rule.getPrivacyType() == null || rule.getPrivacyType().isBlank()) {
                continue;
            }
            ruleMap.put(normalizeType(rule.getPrivacyType()), rule);
        }
        return new EffectivePrivacyPolicy(ruleMap);
    }

    /**
     * 规范化隐私类型。
     */
    private String normalizeType(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 隐私策略运行时视图。
     */
    public static class EffectivePrivacyPolicy {

        private final Map<String, PrivacyPolicyRuleEntity> rules;

        private EffectivePrivacyPolicy(Map<String, PrivacyPolicyRuleEntity> rules) {
            this.rules = rules == null ? Map.of() : rules;
        }

        /**
         * 构建默认策略。
         */
        public static EffectivePrivacyPolicy defaultPolicy() {
            return new EffectivePrivacyPolicy(Map.of());
        }

        /**
         * 判断指定隐私类型是否需要保护。
         * <p>
         * S3 密钥类始终返回 true；其他类型没有显式规则时默认开启保护。
         *
         * @param type 隐私类型
         * @return 是否启用保护
         */
        public boolean shouldProtect(String type) {
            String normalized = normalize(type);
            if (FORCE_BLOCK_TYPES.contains(normalized)) {
                return true;
            }
            PrivacyPolicyRuleEntity rule = rules.get(normalized);
            if (rule == null) {
                rule = fallbackRule(normalized);
            }
            if (rule == null) {
                return true;
            }
            if (Boolean.TRUE.equals(rule.getForced())) {
                return true;
            }
            if (Boolean.FALSE.equals(rule.getEnabled())) {
                return false;
            }
            return !"NONE".equalsIgnoreCase(rule.getAction());
        }

        /**
         * 获取指定隐私类型的处理动作。
         *
         * @param type 隐私类型
         * @return 处理动作，默认 MASK
         */
        public String actionOf(String type) {
            String normalized = normalize(type);
            PrivacyPolicyRuleEntity rule = rules.get(normalized);
            if (rule == null) {
                rule = fallbackRule(normalized);
            }
            if (rule == null || rule.getAction() == null || rule.getAction().isBlank()) {
                return "MASK";
            }
            return rule.getAction().trim().toUpperCase(Locale.ROOT);
        }

        /**
         * 获取 Prompt 注入检测命中后的处理动作。
         * <p>
         * 缺省为 BLOCK，确保未配置时保持原有强拦截语义。
         */
        public String promptInjectionAction(String source) {
            String type = "MODEL".equalsIgnoreCase(source)
                    ? PROMPT_INJECTION_MODEL
                    : PROMPT_INJECTION_BLACKLIST;
            PrivacyPolicyRuleEntity rule = rules.get(type);
            if (rule == null || rule.getAction() == null || rule.getAction().isBlank()) {
                return "BLOCK";
            }
            return rule.getAction().trim().toUpperCase(Locale.ROOT);
        }

        /**
         * 兼容 NER 标签与策略规则命名差异。
         */
        private PrivacyPolicyRuleEntity fallbackRule(String normalized) {
            if ("PER".equals(normalized)) {
                return rules.get("NAME");
            }
            if ("LOC".equals(normalized)) {
                return rules.get("LOCATION");
            }
            return null;
        }

        private String normalize(String type) {
            return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        }
    }
}
