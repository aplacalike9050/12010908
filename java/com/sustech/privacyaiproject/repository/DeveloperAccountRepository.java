package com.sustech.privacyaiproject.repository;

import com.sustech.privacyaiproject.domain.entity.DeveloperAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface DeveloperAccountRepository extends JpaRepository<DeveloperAccountEntity, Long> {

    Optional<DeveloperAccountEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /**
     * 同步 BIGSERIAL 序列，兼容手工插入固定 ID 的测试种子数据。
     */
    @Query(value = """
            SELECT setval(
                pg_get_serial_sequence('privacy_ai_schema.developer_account', 'id'),
                GREATEST(COALESCE((SELECT MAX(id) FROM privacy_ai_schema.developer_account), 1), 1),
                COALESCE((SELECT MAX(id) FROM privacy_ai_schema.developer_account), 0) > 0
            )
            """, nativeQuery = true)
    Long syncIdSequence();
}
