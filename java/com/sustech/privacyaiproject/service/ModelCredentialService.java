package com.sustech.privacyaiproject.service;

import com.sustech.privacyaiproject.common.auth.GatewayAuthContext;
import com.sustech.privacyaiproject.common.exception.BizException;
import com.sustech.privacyaiproject.domain.dto.openai.OpenAiChatCompletionRequest;
import com.sustech.privacyaiproject.domain.entity.ModelCredentialEntity;
import com.sustech.privacyaiproject.domain.enums.CredentialMode;
import com.sustech.privacyaiproject.repository.ModelCredentialRepository;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 模型凭证解析服务。
 * <p>
 * 根据请求中的 credential_mode 决定使用系统默认模型 Bean，或使用开发者自定义模型密钥动态构建 OpenAI 兼容客户端。
 */
@Service
@RequiredArgsConstructor
public class ModelCredentialService {

    private final RoutingService routingService;
    private final ModelCredentialRepository modelCredentialRepository;
    private final CredentialCryptoService credentialCryptoService;

    /**
     * 解析非流式模型客户端。
     *
     * @param authContext 网关认证上下文
     * @param request OpenAI 兼容请求
     * @return 非流式模型客户端
     */
    public ChatModel resolveChatModel(GatewayAuthContext authContext, OpenAiChatCompletionRequest request) {
        if (CredentialMode.from(request.getCredentialMode()) == CredentialMode.SYSTEM_DEFAULT) {
            return routingService.resolveModel(request.getModel());
        }
        ModelCredentialEntity credential = loadClientCredential(authContext, request);
        return OpenAiChatModel.builder()
                .baseUrl(resolveBaseUrl(credential))
                .apiKey(credentialCryptoService.decryptApiKey(credential.getEncryptedApiKey()))
                .modelName(resolveModelName(credential, request))
                .build();
    }

    /**
     * 解析流式模型客户端。
     *
     * @param authContext 网关认证上下文
     * @param request OpenAI 兼容请求
     * @return 流式模型客户端
     */
    public StreamingChatModel resolveStreamingChatModel(GatewayAuthContext authContext, OpenAiChatCompletionRequest request) {
        if (CredentialMode.from(request.getCredentialMode()) == CredentialMode.SYSTEM_DEFAULT) {
            return routingService.resolveStreamingModel(request.getModel());
        }
        ModelCredentialEntity credential = loadClientCredential(authContext, request);
        return OpenAiStreamingChatModel.builder()
                .baseUrl(resolveBaseUrl(credential))
                .apiKey(credentialCryptoService.decryptApiKey(credential.getEncryptedApiKey()))
                .modelName(resolveModelName(credential, request))
                .build();
    }

    /**
     * 加载并校验当前客户端可用的自定义模型密钥。
     */
    private ModelCredentialEntity loadClientCredential(GatewayAuthContext authContext, OpenAiChatCompletionRequest request) {
        if (authContext == null || authContext.gatewayClientId() == null) {
            throw BizException.unauthorized("网关客户端认证上下文无效");
        }
        if (request.getModelCredentialId() == null) {
            throw BizException.badRequest("使用CLIENT_PROVIDED模式时必须传入model_credential_id");
        }
        return modelCredentialRepository.findByIdAndGatewayClientIdAndStatus(
                        request.getModelCredentialId(),
                        authContext.gatewayClientId(),
                        "ACTIVE"
                )
                .orElseThrow(() -> BizException.notFound("自定义模型密钥不存在或已停用"));
    }

    private String resolveBaseUrl(ModelCredentialEntity credential) {
        if (credential.getBaseUrl() == null || credential.getBaseUrl().isBlank()) {
            return "https://api.openai.com/v1";
        }
        return credential.getBaseUrl().trim();
    }

    private String resolveModelName(ModelCredentialEntity credential, OpenAiChatCompletionRequest request) {
        if (request.getModel() != null && !request.getModel().isBlank()) {
            return request.getModel().trim();
        }
        return credential.getModelName();
    }
}
