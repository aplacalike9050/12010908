package com.sustech.privacyaiproject.repository;

import com.sustech.privacyaiproject.domain.entity.ModelCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 开发者自定义模型密钥仓储。
 */
public interface ModelCredentialRepository extends JpaRepository<ModelCredentialEntity, Long> {

    /**
     * 查询指定客户端下的可用模型密钥。
     *
     * @param id 模型密钥记录 ID
     * @param gatewayClientId 网关客户端主键
     * @param status 密钥状态
     * @return 可用模型密钥
     */
    Optional<ModelCredentialEntity> findByIdAndGatewayClientIdAndStatus(Long id, Long gatewayClientId, String status);

    /**
     * 查询指定客户端下的全部模型密钥。
     *
     * @param gatewayClientId 网关客户端主键
     * @return 模型密钥列表
     */
    List<ModelCredentialEntity> findByGatewayClientIdOrderByCreateTimeDesc(Long gatewayClientId);
}
