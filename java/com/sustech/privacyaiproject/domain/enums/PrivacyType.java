package com.sustech.privacyaiproject.domain.enums;

import lombok.Getter;

/**
 * 隐私类型枚举
 * 用于定义系统支持检测的敏感信息类型
 */
@Getter
public enum PrivacyType {

    PHONE("PHONE", "手机号码"),
    LANDLINE("LANDLINE", "固定电话"),
    ID_CARD("ID_CARD", "身份证号"),
    PASSPORT("PASSPORT", "护照号"),
    TRAVEL_PERMIT("TRAVEL_PERMIT", "港澳台通行证"),
    EMAIL("EMAIL", "电子邮箱"),
    BANK_CARD("BANK_CARD", "银行卡号"),
    IPV4("IPV4", "IPv4地址"),
    MAC("MAC", "MAC地址"),
    SSN("SSN", "美国社会安全号"),
    NAME("NAME", "姓名"), // 这是一个难点，需要 BERT
    API_KEY("API_KEY", "API 密钥"), // 高危，需要拦截
    JWT_TOKEN("JWT_TOKEN", "JWT令牌"),
    AWS_ACCESS_KEY("AWS_ACCESS_KEY", "AWS访问密钥"),
    PRIVATE_KEY_BLOCK("PRIVATE_KEY_BLOCK", "私钥内容块"),
    LOCATION("LOCATION", "详细地址");

    private final String code;
    private final String description;

    PrivacyType(String code, String description) {
        this.code = code;
        this.description = description;
    }
}