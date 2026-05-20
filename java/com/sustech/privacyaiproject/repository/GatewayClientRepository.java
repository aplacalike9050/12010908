package com.sustech.privacyaiproject.repository;

import com.sustech.privacyaiproject.domain.entity.GatewayClientEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface GatewayClientRepository extends JpaRepository<GatewayClientEntity, Long> {

    Optional<GatewayClientEntity> findByClientId(String clientId);

    Optional<GatewayClientEntity> findByApiKeyHash(String apiKeyHash);

    List<GatewayClientEntity> findByOwnerDeveloperIdOrderByCreateTimeDesc(Long ownerDeveloperId);

    boolean existsByClientId(String clientId);

    /**
     * 同步 BIGSERIAL 序列，兼容手工插入固定 ID 的测试种子数据。
     */
    @Query(value = """
            SELECT setval(
                pg_get_serial_sequence('privacy_ai_schema.gateway_client', 'id'),
                GREATEST(COALESCE((SELECT MAX(id) FROM privacy_ai_schema.gateway_client), 1), 1),
                COALESCE((SELECT MAX(id) FROM privacy_ai_schema.gateway_client), 0) > 0
            )
            """, nativeQuery = true)
    Long syncIdSequence();
}
