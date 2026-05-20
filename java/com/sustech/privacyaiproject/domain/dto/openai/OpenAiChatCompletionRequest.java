package com.sustech.privacyaiproject.domain.dto.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 兼容请求体。
 * <p>
 * 网关只扩展少量隐私配置字段，其余字段尽量保持 OpenAI 协议命名和语义。
 */
@Data
public class OpenAiChatCompletionRequest {

    /**
     * 模型名称或网关模型别名。
     */
    private String model;

    /**
     * 调用方传入的结构化历史消息数组。
     */
    private List<OpenAiChatMessage> messages;

    /**
     * 是否使用流式输出。
     */
    private Boolean stream = false;

    private Double temperature;

    @JsonProperty("top_p")
    private Double topP;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @JsonProperty("presence_penalty")
    private Double presencePenalty;

    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;

    /**
     * OpenAI 兼容 metadata，建议调用方放入业务 requestId、conversationId 等扩展信息。
     */
    private Map<String, Object> metadata;

    /**
     * 网关扩展字段：隐私策略档案 ID。
     */
    @JsonProperty("privacy_policy_id")
    private Long privacyPolicyId;

    /**
     * 网关扩展字段：模型密钥模式，默认 SYSTEM_DEFAULT。
     */
    @JsonProperty("credential_mode")
    private String credentialMode;

    /**
     * 网关扩展字段：开发者自定义模型密钥记录 ID。
     */
    @JsonProperty("model_credential_id")
    private Long modelCredentialId;
}
