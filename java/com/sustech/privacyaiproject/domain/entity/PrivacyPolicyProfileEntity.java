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

import java.time.LocalDateTime;

/**
 * 隐私策略档案实体。
 * <p>
 * 一个策略档案包含多条隐私规则，可绑定到指定网关客户端，也可作为系统级默认策略。
 */
@Getter
@Setter
@Entity
@Table(
        name = "privacy_policy_profile",
        schema = "privacy_ai_schema",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_policy_profile_name_version", columnNames = {
                        "gateway_client_id", "profile_name", "version"
                })
        }
)
public class PrivacyPolicyProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "gateway_client_id")
    private Long gatewayClientId;

    @Column(name = "profile_name", nullable = false, length = 120)
    private String profileName;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "is_default", nullable = false)
    private Boolean defaultProfile = false;

    @Column(nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    /**
     * 创建策略档案前填充时间戳。
     */
    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createTime = now;
        updateTime = now;
    }

    /**
     * 更新策略档案前刷新更新时间。
     */
    @PreUpdate
    public void preUpdate() {
        updateTime = LocalDateTime.now();
    }
}
