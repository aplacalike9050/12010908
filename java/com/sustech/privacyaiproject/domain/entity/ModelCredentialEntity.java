package com.sustech.privacyaiproject.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 开发者自定义模型密钥实体。
 * <p>
 * 用于保存开发者绑定的 OpenAI 兼容模型配置，API Key 必须以加密密文形式落库。
 */
@Getter
@Setter
@Entity
@Table(name = "model_credential", schema = "privacy_ai_schema")
public class ModelCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "gateway_client_id", nullable = false)
    private Long gatewayClientId;

    @Column(name = "credential_name", nullable = false, length = 120)
    private String credentialName;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(name = "base_url", length = 512)
    private String baseUrl;

    @Column(name = "model_name", nullable = false, length = 120)
    private String modelName;

    @Column(name = "encrypted_api_key", nullable = false, columnDefinition = "TEXT")
    private String encryptedApiKey;

    @Column(nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    /**
     * 创建记录前填充时间戳。
     */
    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createTime = now;
        updateTime = now;
    }

    /**
     * 更新记录前刷新更新时间。
     */
    @PreUpdate
    public void preUpdate() {
        updateTime = LocalDateTime.now();
    }
}
