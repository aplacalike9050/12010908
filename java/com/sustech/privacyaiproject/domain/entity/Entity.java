package com.sustech.privacyaiproject.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 命名实体对象
 * 用于描述从文本中提取出的敏感实体
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Entity {
    /**
     * 实体内容 (例如："张三")
     */
    private String text;

    /**
     * 实体类型 (例如："PER"-人名, "ORG"-组织, "LOC"-地点)
     * BERT 模型通常输出这种标准缩写
     */
    private String type;

    /**
     * 在原文本中的起始索引 (用于后续替换)
     */
    private int start;

    /**
     * 在原文本中的结束索引
     */
    private int end;
}