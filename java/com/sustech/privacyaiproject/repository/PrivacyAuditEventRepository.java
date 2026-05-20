package com.sustech.privacyaiproject.repository;

import com.sustech.privacyaiproject.domain.entity.PrivacyAuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 隐私审计事件仓储。
 */
public interface PrivacyAuditEventRepository extends JpaRepository<PrivacyAuditEventEntity, Long> {

    /**
     * 按调用成功状态统计审计事件数量。
     *
     * @param success 是否成功
     * @return 事件数量
     */
    long countBySuccess(Boolean success);

    /**
     * 按拦截状态统计审计事件数量。
     *
     * @param blocked 是否拦截
     * @return 事件数量
     */
    long countByBlocked(Boolean blocked);
}
