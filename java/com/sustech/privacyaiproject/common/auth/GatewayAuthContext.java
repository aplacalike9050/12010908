package com.sustech.privacyaiproject.common.auth;

public record GatewayAuthContext(
        Long gatewayClientId,
        String clientId,
        Long ownerDeveloperId,
        String clientName,
        Long defaultPolicyProfileId,
        int rateLimitPerMinute,
        int concurrentLimit
) {
}
