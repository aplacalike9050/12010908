package com.sustech.privacyaiproject.repository;

import com.sustech.privacyaiproject.domain.entity.PrivacyPolicyRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 隐私策略规则仓储。
 */
public interface PrivacyPolicyRuleRepository extends JpaRepository<PrivacyPolicyRuleEntity, Long> {

    /**
     * 查询策略档案下的全部规则。
     *
     * @param policyProfileId 策略档案主键
     * @return 策略规则列表
     */
    List<PrivacyPolicyRuleEntity> findByPolicyProfileId(Long policyProfileId);

    /**
     * 查询策略档案下指定隐私类型的规则。
     *
     * @param policyProfileId 策略档案主键
     * @param privacyType 隐私类型
     * @return 策略规则
     */
    Optional<PrivacyPolicyRuleEntity> findByPolicyProfileIdAndPrivacyType(Long policyProfileId, String privacyType);
}
