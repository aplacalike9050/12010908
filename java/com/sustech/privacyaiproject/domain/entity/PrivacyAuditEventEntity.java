package com.sustech.privacyaiproject.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 隐私网关审计事件实体。
 * <p>
 * 该表是中间件主事实表：核心统计字段长期保留，大文本请求/响应/差异明细通过 JSONB 字段短期保留。
 */
@Getter
@Setter
@Entity
@Table(name = "privacy_audit_event", schema = "privacy_ai_schema")
public class PrivacyAuditEventEntity {

    /**
     * 高频写入主键，使用雪花算法生成，避免数据库自增热点。
     */
    @Id
    private Long id;

    @Column(name = "request_id", length = 96)
    private String requestId;

    @Column(name = "gateway_client_id")
    private Long gatewayClientId;

    @Column(name = "client_id", length = 64)
    private String clientId;

    @Column(name = "openai_request_id", length = 96)
    private String openAiRequestId;

    @Column(name = "credential_mode", nullable = false, length = 32)
    private String credentialMode = "SYSTEM_DEFAULT";

    @Column(name = "model_provider", length = 64)
    private String modelProvider;

    @Column(name = "model_name", length = 120)
    private String modelName;

    @Column(name = "policy_profile_id")
    private Long policyProfileId;

    @Column(name = "policy_version")
    private Integer policyVersion;

    @Column(name = "risk_level", length = 16)
    private String riskLevel;

    @Column(name = "prompt_injection_detected", nullable = false)
    private Boolean promptInjectionDetected = false;

    @Column(name = "privacy_risk_level", length = 16)
    private String privacyRiskLevel;

    @Column(nullable = false)
    private Boolean blocked = false;

    @Column(name = "block_reason", length = 80)
    private String blockReason;

    @Column(nullable = false)
    private Boolean success = true;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "finding_count", nullable = false)
    private Integer findingCount = 0;

    @Column(name = "prompt_injection_score")
    private BigDecimal promptInjectionScore;

    @Column(name = "request_hash", length = 128)
    private String requestHash;

    @Column(name = "response_hash", length = 128)
    private String responseHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_detail_json", columnDefinition = "jsonb")
    private Map<String, Object> requestDetailJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_detail_json", columnDefinition = "jsonb")
    private Map<String, Object> responseDetailJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "diff_detail_json", columnDefinition = "jsonb")
    private Map<String, Object> diffDetailJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "finding_detail_json", columnDefinition = "jsonb")
    private Map<String, Object> findingDetailJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prompt_injection_detail_json", columnDefinition = "jsonb")
    private Map<String, Object> promptInjectionDetailJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resilience_detail_json", columnDefinition = "jsonb")
    private Map<String, Object> resilienceDetailJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadataJson = Map.of();

    @Column(name = "detail_expire_at")
    private LocalDateTime detailExpireAt;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime = LocalDateTime.now();
}
