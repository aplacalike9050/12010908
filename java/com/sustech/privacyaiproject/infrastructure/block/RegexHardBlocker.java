package com.sustech.privacyaiproject.infrastructure.block;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正则初筛组件。
 * <p>
 * 作用：
 * 1) 极速匹配结构化敏感数据（手机号、身份证、邮箱、API Key 等）；
 * 2) 命中高危信息（S3）时直接拦截；
 * 3) 为后续策略引擎提供结构化命中列表。
 */
@Component
public class RegexHardBlocker {

    /** 普通敏感规则（S2） */
    private static final List<RegexRule> NORMAL_RULES = List.of(
            // 手机号：大陆纯数字/分隔符/区号；美国格式
            new RegexRule("PHONE", Pattern.compile("(?<!\\d)(?:\\+?86[-\\s]?)?1[3-9]\\d[-\\s]?\\d{4}[-\\s]?\\d{4}(?!\\d)")),
            new RegexRule("PHONE", Pattern.compile("(?<!\\d)(?:\\+?1[-\\s]?)?(?:\\(?[2-9]\\d{2}\\)?[-\\s]?)?[2-9]\\d{2}[-\\s]?\\d{4}(?!\\d)")),
            // 固定电话
            new RegexRule("LANDLINE", Pattern.compile("(?<!\\d)0\\d{2,3}[-\\s]?\\d{7,8}(?!\\d)")),
            // 证件
            new RegexRule("ID_CARD", Pattern.compile("(?<!\\d)(\\d{15}|\\d{17}[0-9Xx])(?!\\d)")),
            new RegexRule("PASSPORT", Pattern.compile("(?<![A-Za-z0-9])[EGP]\\d{7,8}(?![A-Za-z0-9])")),
            new RegexRule("PASSPORT", Pattern.compile("(?<![A-Za-z0-9])[A-Z]{1,2}\\d{6,9}(?![A-Za-z0-9])")),
            new RegexRule("PASSPORT", Pattern.compile("(?i)(?:passport\\s*(?:number|no\\.?|id)?|护照(?:号|号码)?)\\s*(?:为|是|:|=|#)?\\s*[A-Z0-9]{6,12}")),
            new RegexRule("TRAVEL_PERMIT", Pattern.compile("(?<![A-Za-z0-9])[HM]\\d{8,10}(?![A-Za-z0-9])")),
            // 邮箱
            new RegexRule("EMAIL", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")),
            // 财务
            new RegexRule("BANK_CARD", Pattern.compile("(?<!\\d)(?:\\d[\\s\\-]?){13,19}(?!\\d)")),
            // 网络与设备
            new RegexRule("IPV4", Pattern.compile("\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b")),
            new RegexRule("IPV4", Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])")),
            new RegexRule("IPV6", Pattern.compile("(?i)(?<![0-9A-F:])(?:[0-9A-F]{1,4}:){7}[0-9A-F]{1,4}(?![0-9A-F:])")),
            new RegexRule("IPV6", Pattern.compile("(?i)(?<![0-9A-F:])(?:(?:[0-9A-F]{1,4}:){1,7}:|:(?::[0-9A-F]{1,4}){1,7}|(?:[0-9A-F]{1,4}:){1,6}:[0-9A-F]{1,4})(?![0-9A-F:])")),
            new RegexRule("MAC", Pattern.compile("(?i)(?<![0-9A-F])(?:[0-9A-F]{2}[:-]){5}[0-9A-F]{2}(?![0-9A-F])")),
            new RegexRule("MAC", Pattern.compile("(?i)(?<![0-9A-F])(?:[0-9A-F]{4}\\.){2}[0-9A-F]{4}(?![0-9A-F])")),
            // 国际化英文隐私
            new RegexRule("SSN", Pattern.compile("\\b(?!000|666)[0-8][0-9]{2}-(?!00)[0-9]{2}-(?!0000)[0-9]{4}\\b"))
    );

    /** 高危规则（S3） */
    private static final List<RegexRule> HIGH_RISK_RULES = List.of(
            new RegexRule("API_KEY", Pattern.compile(
                    "(?i)(sk-[A-Za-z0-9\\-_]{16,}|api[_-]?key\\s*[:=]\\s*[A-Za-z0-9\\-_]{10,}|secret\\s*[:=]\\s*[A-Za-z0-9\\-_]{10,})"
            )),
            new RegexRule("API_KEY", Pattern.compile(
                    "(?<![A-Za-z0-9_\\-])(?:sk-proj-[A-Za-z0-9_\\-]{20,}|sk_live_[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,}|glpat-[A-Za-z0-9_\\-]{16,}|xox[baprs]-[A-Za-z0-9\\-]{20,}|xapp-[A-Za-z0-9\\-]{20,}|SG\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}|app_secret_[A-Za-z0-9]{16,}|npm_[A-Za-z0-9]{20,}|AIzaSy[A-Za-z0-9_\\-]{20,}|AK-[A-Za-z0-9_\\-]{20,}|amap_key_[A-Za-z0-9]{16,}|figd_[A-Za-z0-9]{20,}|lin_api_[A-Za-z0-9]{20,}|dd_api_[A-Za-z0-9]{20,}|Ali_Secret_[A-Za-z0-9]{20,})(?![A-Za-z0-9_\\-])"
            )),
            new RegexRule("API_KEY", Pattern.compile(
                    "(?i)(?:api\\s*v?3?\\s*(?:key|密钥)|secret\\s*key|auth\\s*token|bot\\s*token|personal\\s+access\\s+token|private\\s+token|access[_-]?token|鉴权\\s*token|密钥是|key\\s*(?:是|:|=)|token\\s*(?:是|:|=))\\s*[:：]?\\s*[A-Za-z0-9_./+\\-=]{24,}"
            )),
            new RegexRule("API_KEY", Pattern.compile(
                    "(?i)(?:Authorization\\s*:\\s*Bearer|Bearer)\\s*[A-Za-z0-9_./+\\-=]{24,}"
            )),
            new RegexRule("API_KEY", Pattern.compile(
                    "(?<![A-Za-z0-9_\\-])(?:mfa\\.[A-Za-z0-9_\\-]{20,}|[A-Za-z0-9_\\-]{24}\\.[A-Za-z0-9_\\-]{6}\\.[A-Za-z0-9_\\-]{20,})(?![A-Za-z0-9_\\-])"
            )),
            new RegexRule("API_KEY", Pattern.compile(
                    "(?<![A-Za-z0-9_\\-])(?:[A-Za-z0-9_\\-]{16,}\\.[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{20,})(?![A-Za-z0-9_\\-])"
            )),
            // 完整 JWT（三段）
            new RegexRule("JWT_TOKEN", Pattern.compile(
                    "(?<![A-Za-z0-9\\-_])eyJ[A-Za-z0-9\\-_]{8,}\\.[A-Za-z0-9\\-_]{8,}\\.[A-Za-z0-9\\-_]{8,}(?![A-Za-z0-9\\-_])"
            )),
            new RegexRule("JWT_TOKEN", Pattern.compile(
                    "(?i)(?:Authorization\\s*:\\s*Bearer|Bearer)\\s*eyJ[A-Za-z0-9\\-_]{8,}\\.[A-Za-z0-9\\-_]{2,}(?:\\.[A-Za-z0-9\\-_]{0,})?"
            )),
            new RegexRule("JWT_TOKEN", Pattern.compile(
                    "(?i)JWT\\s*eyJ[A-Za-z0-9\\-_]{8,}\\.[A-Za-z0-9\\-_]{2,}(?:\\.[A-Za-z0-9\\-_]{0,})?"
            )),
            // 抓包中常见的截断 JWT（两段或尾部省略号）
            new RegexRule("JWT_TOKEN", Pattern.compile(
                    "(?<![A-Za-z0-9\\-_])eyJ[A-Za-z0-9\\-_]{8,}\\.[A-Za-z0-9\\-_]{2,}(?:\\.[A-Za-z0-9\\-_]{0,})?(?:\\.\\.\\.)?(?![A-Za-z0-9\\-_])"
            )),
            new RegexRule("AWS_ACCESS_KEY", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new RegexRule("AWS_ACCESS_KEY", Pattern.compile(
                    "(?i)(?:Access\\s*Key|AWS\\s*Key)[\\s\\S]{0,32}(?:AKIA|ASIA)[0-9A-Z]{16}"
            )),
            new RegexRule("AWS_ACCESS_KEY", Pattern.compile(
                    "(?i)(?:AWS_SECRET_ACCESS_KEY|AWS\\s+Secret\\s+Access\\s+Key|Secret\\s+Access\\s+Key)\\s*(?:为|是|:|=)?\\s*[A-Za-z0-9/+]{32,}"
            )),
            new RegexRule("AWS_ACCESS_KEY", Pattern.compile(
                    "(?i)(?:AWS\\s*Secret|AWS\\s*Key|IAM\\s*用户的\\s*Secret|Secret\\s*Access\\s*Key)[\\s\\S]{0,48}[A-Za-z0-9/+]{40}"
            )),
            new RegexRule("AWS_ACCESS_KEY", Pattern.compile(
                    "(?i)(?:Secret\\s*Key|AWS\\s*Secret|AWS\\s*Key|IAM\\s*用户的\\s*Secret|Secret)[\\s\\S]{0,24}[A-Za-z0-9/+]{32,}"
            )),
            new RegexRule("AWS_ACCESS_KEY", Pattern.compile(
                    "(?i)[A-Za-z0-9/+]{40,}\\s*(?:as\\s+my\\s+AWS\\s+Secret|作为\\s*AWS\\s*Secret|AWS\\s*Secret)"
            )),
            new RegexRule("AWS_ACCESS_KEY", Pattern.compile(
                    "(?i)Secret[A-Za-z0-9/+]{32,}"
            )),
            new RegexRule("PRIVATE_KEY_BLOCK", Pattern.compile(
                    "-----BEGIN\\s*(?:(?:RSA|EC|OPENSSH|DSA|ENCRYPTED)\\s*)?PRIVATE\\s*KEY-----[\\s\\S]*?-----END\\s*(?:(?:RSA|EC|OPENSSH|DSA|ENCRYPTED)\\s*)?PRIVATE\\s*KEY-----"
            )),
            new RegexRule("PRIVATE_KEY_BLOCK", Pattern.compile(
                    "-----BEGIN\\s*PGP\\s*PRIVATE\\s*KEY\\s*BLOCK-----[\\s\\S]*?-----END\\s*PGP\\s*PRIVATE\\s*KEY\\s*BLOCK-----"
            )),
            new RegexRule("PRIVATE_KEY_BLOCK", Pattern.compile(
                    "-----BEGIN\\s*(?:(?:RSA|EC|OPENSSH|DSA|ENCRYPTED|PGP)\\s*)?PRIVATE\\s*(?:KEY|KEY\\s*BLOCK)-----"
            )),
            new RegexRule("PRIVATE_KEY_BLOCK", Pattern.compile(
                    "-----END\\s*(?:RSA|EC|OPENSSH|DSA|ENCRYPTED)?\\s*PRIVATE\\s*KEY-----"
            ))
    );

    /**
     * 对输入文本执行正则扫描。
     *
     * @param text 原始文本
     * @return 扫描结果（包含命中实体与是否触发高危拦截）
     */
    public RegexScanResult scan(String text) {
        String safeText = text == null ? "" : text;
        List<RegexFinding> findings = new ArrayList<>();
        Set<String> deduplicate = new HashSet<>();

        for (RegexRule rule : NORMAL_RULES) {
            collectNormalFindings(safeText, rule, findings, deduplicate);
        }

        List<RegexFinding> highRiskFindings = new ArrayList<>();
        for (RegexRule rule : HIGH_RISK_RULES) {
            collectFindings(safeText, rule.getPattern(), rule.getType(), highRiskFindings, deduplicate);
        }
        boolean blocked = !highRiskFindings.isEmpty();
        findings.addAll(highRiskFindings);

        return new RegexScanResult(blocked, findings);
    }

    /**
     * 通用正则命中收集方法。
     */
    private void collectFindings(String text, Pattern pattern, String type, List<RegexFinding> output, Set<String> deduplicate) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String key = type + "|" + matcher.start() + "|" + matcher.end();
            if (deduplicate.add(key)) {
                output.add(new RegexFinding(type, matcher.group(), matcher.start(), matcher.end()));
            }
        }
    }

    private void collectNormalFindings(String text, RegexRule rule, List<RegexFinding> output, Set<String> deduplicate) {
        Matcher matcher = rule.getPattern().matcher(text);
        while (matcher.find()) {
            if ("BANK_CARD".equals(rule.getType())) {
                if (overlapsExistingType(output, matcher.start(), matcher.end(), "ID_CARD")
                        || !validBankCardCandidate(matcher.group())) {
                    continue;
                }
            }
            String key = rule.getType() + "|" + matcher.start() + "|" + matcher.end();
            if (deduplicate.add(key)) {
                output.add(new RegexFinding(rule.getType(), matcher.group(), matcher.start(), matcher.end()));
            }
        }
    }

    private boolean overlapsExistingType(List<RegexFinding> findings, int start, int end, String type) {
        for (RegexFinding finding : findings) {
            if (type.equals(finding.getType()) && start < finding.getEnd() && end > finding.getStart()) {
                return true;
            }
        }
        return false;
    }

    private boolean validBankCardCandidate(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        if (digits.startsWith("4") || digits.startsWith("34") || digits.startsWith("37")
                || digits.startsWith("35") || digits.startsWith("6011") || digits.startsWith("65")
                || digits.startsWith("62")) {
            return true;
        }
        int prefix2 = Integer.parseInt(digits.substring(0, 2));
        if (prefix2 >= 51 && prefix2 <= 55) {
            return true;
        }
        int prefix4 = Integer.parseInt(digits.substring(0, 4));
        return prefix4 >= 2221 && prefix4 <= 2720;
    }

    @Data
    @AllArgsConstructor
    private static class RegexRule {
        private String type;
        private Pattern pattern;
    }

    /**
     * 初筛命中结果。
     */
    @Data
    @AllArgsConstructor
    public static class RegexFinding {
        private String type;
        private String value;
        private int start;
        private int end;
    }

    /**
     * 初筛整体结果。
     */
    @Data
    @AllArgsConstructor
    public static class RegexScanResult {
        private boolean blocked;
        private List<RegexFinding> findings;
    }
}
