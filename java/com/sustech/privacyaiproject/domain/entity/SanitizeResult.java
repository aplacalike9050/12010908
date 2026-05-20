package com.sustech.privacyaiproject.domain.entity;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 脱敏处理结果值对象 (Value Object)
 * 包含处理后的文本和还原所需的映射表
 */
@Data
@Builder
public class SanitizeResult {

    /**
     * 安全的、已脱敏的 Prompt
     * 发送给 AI 的是这个字段
     */
    private String safePrompt;

    /**
     * 隐私映射表 (Key: 占位符, Value: 真实值)
     * 例如：{"[PII_PHONE_a8b3_1]": "13800138000"}
     * 这个 Map 将会被存入 Redis
     */
    private Map<String, String> originalMapping;

    /**
     * 本次请求中新识别并执行保护的隐私字段详情。
     */
    private List<Map<String, Object>> findingDetails;

    /**
     * 是否包含敏感信息
     * 用于前端展示“盾牌”图标状态
     */
    private boolean hasSensitiveInfo;
}