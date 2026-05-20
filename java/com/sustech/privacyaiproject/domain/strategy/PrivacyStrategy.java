package com.sustech.privacyaiproject.domain.strategy;
//接口，定义清洗和还原方法
import com.sustech.privacyaiproject.domain.entity.SanitizeResult;

/**
 * 隐私保护策略接口
 * 定义了所有脱敏算法必须遵循的标准
 */
public interface PrivacyStrategy {

    /**
     * 执行脱敏策略
     * @param originalText 用户原始输入
     * @return 脱敏结果（包含安全文本和映射表）
     */
    SanitizeResult sanitize(String originalText);

    /**
     * 获取策略类型标识
     * @return 例如 "MASK" 或 "SYNTHESIS"
     */
    String getStrategyType();
}