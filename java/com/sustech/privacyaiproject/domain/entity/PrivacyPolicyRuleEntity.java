package com.sustech.privacyaiproject.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 隐私策略规则实体。
 * <p>
 * 用于描述某个隐私类型在指定策略档案下的风险等级、处理动作和是否允许关闭。
 */
@Getter
@Setter
@Entity
@Table(
        name = "privacy_policy_rule",
        schema = "privacy_ai_schema",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_policy_rule_type", columnNames = {
                        "policy_profile_id", "privacy_type"
                })
        }
)
public class PrivacyPolicyRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_profile_id", nullable = false)
    private Long policyProfileId;

    @Column(name = "privacy_type", nullable = false, length = 64)
    private String privacyType;

    @Column(name = "risk_level", nullable = false, length = 16)
    private String riskLevel;

    @Column(nullable = false, length = 32)
    private String action;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(nullable = false)
    private Boolean forced = false;

    @Column(name = "rule_source", nullable = false, length = 32)
    private String ruleSource = "SYSTEM";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> configJson = Map.of();

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    /**
     * 创建策略规则前填充时间戳。
     */
    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createTime = now;
        updateTime = now;
    }

    /**
     * 更新策略规则前刷新更新时间。
     */
    @PreUpdate
    public void preUpdate() {
        updateTime = LocalDateTime.now();
    }
}
