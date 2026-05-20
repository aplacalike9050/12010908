package com.sustech.privacyaiproject.repository;

import com.sustech.privacyaiproject.domain.entity.PrivacyPolicyProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 隐私策略档案仓储。
 */
public interface PrivacyPolicyProfileRepository extends JpaRepository<PrivacyPolicyProfileEntity, Long> {

    /**
     * 查询指定客户端的策略档案列表。
     *
     * @param gatewayClientId 网关客户端主键
     * @return 策略档案列表
     */
    List<PrivacyPolicyProfileEntity> findByGatewayClientIdOrderByCreateTimeDesc(Long gatewayClientId);

    /**
     * 查询指定客户端的默认策略档案。
     *
     * @param gatewayClientId 网关客户端主键
     * @param defaultProfile 是否默认策略
     * @param status 策略状态
     * @return 默认策略档案
     */
    Optional<PrivacyPolicyProfileEntity> findFirstByGatewayClientIdAndDefaultProfileAndStatus(
            Long gatewayClientId,
            Boolean defaultProfile,
            String status
    );

    /**
     * 同步 BIGSERIAL 序列，兼容手工插入固定 ID 的测试种子数据。
     */
    @Query(value = """
            SELECT setval(
                pg_get_serial_sequence('privacy_ai_schema.privacy_policy_profile', 'id'),
                GREATEST(COALESCE((SELECT MAX(id) FROM privacy_ai_schema.privacy_policy_profile), 1), 1),
                COALESCE((SELECT MAX(id) FROM privacy_ai_schema.privacy_policy_profile), 0) > 0
            )
            """, nativeQuery = true)
    Long syncIdSequence();
}
