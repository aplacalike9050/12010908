package com.sustech.privacyaiproject.service;

import com.sustech.privacyaiproject.common.auth.GatewayAuthContext;
import com.sustech.privacyaiproject.common.exception.BizException;
import com.sustech.privacyaiproject.common.util.ApiKeyHashUtil;
import com.sustech.privacyaiproject.common.util.ApiKeyUtil;
import com.sustech.privacyaiproject.domain.entity.GatewayClientEntity;
import com.sustech.privacyaiproject.repository.GatewayClientRepository;
import com.sustech.privacyaiproject.repository.PrivacyPolicyProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GatewayClientService {

    private final GatewayClientRepository gatewayClientRepository;
    private final PrivacyPolicyProfileRepository policyProfileRepository;

    @Transactional
    public CreatedGatewayClient createClient(Long ownerDeveloperId, String clientName) {
        if (ownerDeveloperId == null) {
            throw BizException.badRequest("开发者账号ID不能为空");
        }
        if (clientName == null || clientName.isBlank()) {
            throw BizException.badRequest("客户端名称不能为空");
        }
        gatewayClientRepository.syncIdSequence();
        String apiKey = ApiKeyUtil.generateGatewayApiKey();
        GatewayClientEntity client = new GatewayClientEntity();
        client.setClientId(generateClientId());
        client.setOwnerDeveloperId(ownerDeveloperId);
        client.setClientName(clientName.trim());
        client.setApiKeyHash(ApiKeyHashUtil.hashApiKey(apiKey));
        GatewayClientEntity saved = gatewayClientRepository.save(client);
        return new CreatedGatewayClient(saved.getId(), saved.getClientId(), apiKey);
    }

    public GatewayAuthContext authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw BizException.unauthorized("网关API Key不能为空");
        }
        String hash = ApiKeyHashUtil.hashApiKey(apiKey);
        GatewayClientEntity client = gatewayClientRepository.findByApiKeyHash(hash)
                .orElseThrow(() -> BizException.unauthorized("网关API Key无效"));
        if (!"ACTIVE".equalsIgnoreCase(client.getStatus())) {
            throw BizException.forbidden("网关客户端已停用");
        }
        Long defaultPolicyProfileId = validDefaultPolicyProfileId(client);
        return new GatewayAuthContext(
                client.getId(),
                client.getClientId(),
                client.getOwnerDeveloperId(),
                client.getClientName(),
                defaultPolicyProfileId,
                client.getRateLimitPerMinute(),
                client.getConcurrentLimit()
        );
    }

    private Long validDefaultPolicyProfileId(GatewayClientEntity client) {
        Long defaultPolicyProfileId = client.getDefaultPolicyProfileId();
        if (defaultPolicyProfileId == null || defaultPolicyProfileId <= 0) {
            return null;
        }
        return policyProfileRepository.findById(defaultPolicyProfileId)
                .filter(profile -> client.getId().equals(profile.getGatewayClientId()))
                .map(profile -> defaultPolicyProfileId)
                .orElse(null);
    }

    private String generateClientId() {
        String clientId;
        do {
            clientId = "gc_" + UUID.randomUUID().toString().replace("-", "");
        } while (gatewayClientRepository.existsByClientId(clientId));
        return clientId;
    }

    public record CreatedGatewayClient(Long id, String clientId, String apiKey) {
    }
}
