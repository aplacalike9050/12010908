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

@Getter
@Setter
@Entity
@Table(
        name = "gateway_client",
        schema = "privacy_ai_schema",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_gateway_client_client_id", columnNames = "client_id"),
                @UniqueConstraint(name = "uk_gateway_client_api_key_hash", columnNames = "api_key_hash")
        }
)
public class GatewayClientEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "owner_developer_id", nullable = false)
    private Long ownerDeveloperId;

    @Column(name = "client_name", nullable = false, length = 120)
    private String clientName;

    @Column(name = "api_key_hash", nullable = false, length = 128)
    private String apiKeyHash;

    @Column(nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "default_policy_profile_id")
    private Long defaultPolicyProfileId;

    @Column(name = "rate_limit_per_minute", nullable = false)
    private Integer rateLimitPerMinute = 60;

    @Column(name = "concurrent_limit", nullable = false)
    private Integer concurrentLimit = 5;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createTime = now;
        updateTime = now;
    }

    @PreUpdate
    public void preUpdate() {
        updateTime = LocalDateTime.now();
    }
}
