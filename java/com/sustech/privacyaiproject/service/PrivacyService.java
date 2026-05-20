package com.sustech.privacyaiproject.service;
//串主编排，执行硬拦截、NER、策略脱敏、路由调用、响应还原。
import com.sustech.privacyaiproject.common.exception.BizException;
import com.sustech.privacyaiproject.common.auth.GatewayAuthContext;
import com.sustech.privacyaiproject.common.exception.HighRiskContentException;
import com.sustech.privacyaiproject.domain.dto.openai.OpenAiChatCompletionRequest;
import com.sustech.privacyaiproject.domain.dto.openai.OpenAiChatMessage;
import com.sustech.privacyaiproject.domain.dto.security.PromptInjectionDetectionResult;
import com.sustech.privacyaiproject.domain.entity.Entity;
import com.sustech.privacyaiproject.domain.entity.SanitizeResult;
import com.sustech.privacyaiproject.common.privacy.PlaceholderCollisionGuard;
import com.sustech.privacyaiproject.infrastructure.block.RegexHardBlocker;
import com.sustech.privacyaiproject.infrastructure.ner.NerService;
import com.sustech.privacyaiproject.infrastructure.redis.PrivacyMappingRepository;
import com.sustech.privacyaiproject.repository.PrivacyPolicyProfileRepository;
import com.sustech.privacyaiproject.security.PromptInjectionGuard;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@Service
public class PrivacyService {

    private static final int MAX_USER_INPUT_CHARS = 20000;
    private static final int SKIP_NER_CHARS = 4000;

    private final RegexHardBlocker regexHardBlocker;
    private final NerService nerService;
    private final PrivacyPolicyEngine privacyPolicyEngine;
    private final PrivacyPolicyService privacyPolicyService;
    private final ModelCredentialService modelCredentialService;
    private final PrivacyMappingRepository mappingRepository;
    private final RestoreService restoreService;
    private final PlaceholderCollisionGuard placeholderCollisionGuard;
    private final PromptInjectionGuard promptInjectionGuard;
    private final ModelResilienceService modelResilienceService;
    private final MockModelService mockModelService;
    private final PrivacyPolicyProfileRepository policyProfileRepository;

    @Value("${privacy.enabled:true}")
    private boolean privacyEnabled;
    @Value("${privacy.regex.enabled:true}")
    private boolean regexEnabled;
    @Value("${privacy.ner.enabled:true}")
    private boolean nerEnabled;

    /**
     * 隐私服务主编排器。
     * 串起：客户端上下文 -> 脱敏 -> 模型调用 -> 响应还原。
     */
    public PrivacyService(RegexHardBlocker regexHardBlocker,
                          NerService nerService,
                          PrivacyPolicyEngine privacyPolicyEngine,
                          PrivacyPolicyService privacyPolicyService,
                          ModelCredentialService modelCredentialService,
                          PrivacyMappingRepository mappingRepository,
                          RestoreService restoreService,
                          PlaceholderCollisionGuard placeholderCollisionGuard,
                          PromptInjectionGuard promptInjectionGuard,
                          ModelResilienceService modelResilienceService,
                          MockModelService mockModelService,
                          PrivacyPolicyProfileRepository policyProfileRepository) {
        this.regexHardBlocker = regexHardBlocker;
        this.nerService = nerService;
        this.privacyPolicyEngine = privacyPolicyEngine;
        this.privacyPolicyService = privacyPolicyService;
        this.modelCredentialService = modelCredentialService;
        this.mappingRepository = mappingRepository;
        this.restoreService = restoreService;
        this.placeholderCollisionGuard = placeholderCollisionGuard;
        this.promptInjectionGuard = promptInjectionGuard;
        this.modelResilienceService = modelResilienceService;
        this.mockModelService = mockModelService;
        this.policyProfileRepository = policyProfileRepository;
    }

    /**
     * 处理 OpenAI 兼容非流式聊天请求。
     * <p>
     * 该方法是新中间件主链路，不再读取或写入 chat_session/chat_message。
     * 历史上下文完全由调用方通过 messages 数组传入，网关仅做隐私保护、模型调用和响应还原。
     *
     * @param authContext 网关客户端认证上下文
     * @param request OpenAI 兼容请求
     * @return 还原后的模型回复与本次映射 key
     */
    public OpenAiProcessResult processOpenAiChat(GatewayAuthContext authContext, OpenAiChatCompletionRequest request) {
        validateOpenAiRequest(authContext, request);
        String mappingKey = resolveOpenAiMappingKey(authContext, request);
        Long policyProfileId = resolvePolicyProfileId(authContext, request);
        SanitizedMessagesResult sanitizedResult = toSanitizedOpenAiMessages(request.getMessages(), mappingKey, policyProfileId);
        String modelText = modelResilienceService.execute(request.getModel(), () -> {
            if (mockModelService.isEnabled()) {
                return mockModelService.chat(request, sanitizedResult.sanitizedModelInput());
            }
            ChatResponse response = modelCredentialService.resolveChatModel(authContext, request).chat(sanitizedResult.messages());
            if (response == null || response.aiMessage() == null || response.aiMessage().text() == null) {
                throw new BizException(502, "模型返回为空");
            }
            return response.aiMessage().text();
        });
        String restored = mockModelService.shouldSkipRestore()
                ? modelText
                : restoreService.restore(modelText, mappingRepository.get(mappingKey));
        return new OpenAiProcessResult(restored, mappingKey, sanitizedResult.promptInjectionDetections(),
                sanitizedResult.privacyRiskLevel(), sanitizedResult.findingDetails());
    }

    /**
     * 处理 OpenAI 兼容流式聊天请求。
     * <p>
     * 该方法同样不写入旧会话/消息表，流式审计由 Controller 在异步线程中记录。
     * tokenConsumer 接收模型原始增量，Controller 负责按 OpenAI SSE 格式输出并执行流式还原。
     *
     * @param authContext 网关客户端认证上下文
     * @param request OpenAI 兼容请求
     * @param tokenConsumer 模型增量回调
     * @return 完整还原文本与本次映射 key
     */
    public OpenAiProcessResult processOpenAiChatStream(GatewayAuthContext authContext,
                                                       OpenAiChatCompletionRequest request,
                                                       Consumer<String> tokenConsumer) {
        validateOpenAiRequest(authContext, request);
        String mappingKey = resolveOpenAiMappingKey(authContext, request);
        Long policyProfileId = resolvePolicyProfileId(authContext, request);
        SanitizedMessagesResult sanitizedResult = toSanitizedOpenAiMessages(request.getMessages(), mappingKey, policyProfileId);
        StreamingOutcome outcome = modelResilienceService.execute(request.getModel(), () -> {
            if (mockModelService.isEnabled()) {
                MockModelService.StreamingResult result = mockModelService.stream(request,
                        sanitizedResult.sanitizedModelInput(), tokenConsumer);
                return new StreamingOutcome(result.text(), "MOCK");
            }
            return streamWithModel(modelCredentialService.resolveStreamingChatModel(authContext, request),
                    sanitizedResult.messages(), tokenConsumer);
        });
        String restored = mockModelService.shouldSkipRestore()
                ? outcome.text()
                : restoreService.restore(outcome.text(), mappingRepository.get(mappingKey));
        return new OpenAiProcessResult(restored, mappingKey, sanitizedResult.promptInjectionDetections(),
                sanitizedResult.privacyRiskLevel(), sanitizedResult.findingDetails());
    }

    /**
     * 解析 OpenAI 请求的映射 key，用于 Redis 隔离隐私映射。
     * <p>
     * 优先使用 metadata.conversation_id；没有时使用 metadata.request_id；仍为空则生成随机 key。
     *
     * @param authContext 网关客户端认证上下文
     * @param request OpenAI 兼容请求
     * @return 带 clientId 前缀的映射 key
     */
    public String resolveOpenAiMappingKey(GatewayAuthContext authContext, OpenAiChatCompletionRequest request) {
        String conversationId = metadataString(request, "conversation_id");
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = metadataString(request, "request_id");
        }
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString().replace("-", "");
        }
        return authContext.clientId() + ":" + conversationId.trim();
    }


    /**
     * 校验 OpenAI 兼容请求的必要字段。
     */
    private void validateOpenAiRequest(GatewayAuthContext authContext, OpenAiChatCompletionRequest request) {
        if (authContext == null || authContext.clientId() == null || authContext.clientId().isBlank()) {
            throw BizException.unauthorized("网关客户端认证上下文无效");
        }
        if (request == null) {
            throw BizException.badRequest("请求体不能为空");
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw BizException.badRequest("messages不能为空");
        }
        boolean hasUserContent = request.getMessages().stream()
                .anyMatch(message -> "user".equalsIgnoreCase(message.getRole())
                        && message.getContent() != null
                        && !message.getContent().isBlank());
        if (!hasUserContent) {
            throw BizException.badRequest("messages至少需要包含一条非空user消息");
        }
    }

    /**
     * 将 OpenAI messages 转换为 LangChain4j 消息，并对用户消息执行隐私脱敏。
     */
    private SanitizedMessagesResult toSanitizedOpenAiMessages(List<OpenAiChatMessage> messages,
                                                              String mappingKey,
                                                              Long policyProfileId) {
        List<ChatMessage> output = new java.util.ArrayList<>();
        List<PromptInjectionDetectionResult> promptInjectionDetections = new java.util.ArrayList<>();
        List<Map<String, Object>> findingDetails = new java.util.ArrayList<>();
        StringBuilder sanitizedModelInput = new StringBuilder();
        String privacyRiskLevel = null;
        for (OpenAiChatMessage message : messages) {
            if (message == null || message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            String role = message.getRole() == null ? "user" : message.getRole().trim().toLowerCase();
            String content = message.getContent();
            switch (role) {
                case "system" -> output.add(SystemMessage.from(content));
                case "assistant" -> output.add(AiMessage.from(content));
                case "user" -> {
                    SanitizedUserInputResult result = sanitizeAndWrapUserInput(mappingKey, content, policyProfileId);
                    output.add(UserMessage.from(result.content()));
                    appendModelInput(sanitizedModelInput, result.content());
                    promptInjectionDetections.addAll(result.promptInjectionDetections());
                    privacyRiskLevel = higherRisk(privacyRiskLevel, result.privacyRiskLevel());
                    findingDetails.addAll(result.findingDetails());
                }
                default -> output.add(UserMessage.from(content));
            }
        }
        return new SanitizedMessagesResult(output, promptInjectionDetections, privacyRiskLevel, findingDetails,
                sanitizedModelInput.toString());
    }

    private void appendModelInput(StringBuilder output, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!output.isEmpty()) {
            output.append(System.lineSeparator());
        }
        output.append(content);
    }

    /**
     * 从 OpenAI metadata 中读取字符串字段。
     */
    private String metadataString(OpenAiChatCompletionRequest request, String key) {
        if (request == null || request.getMetadata() == null || key == null) {
            return null;
        }
        Object value = request.getMetadata().get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 对用户消息执行 Prompt 注入检测、隐私脱敏和结构化边界补丁。
     *
     * @param mappingKey 隐私映射 key
     * @param content 用户输入内容
     * @param policyProfileId 策略档案 ID
     * @return 发送给模型的安全用户消息
     */
    private SanitizedUserInputResult sanitizeAndWrapUserInput(String mappingKey, String content, Long policyProfileId) {
        enforceUserInputLength(content);
        assertNoS3Secrets(content);
        PrivacyPolicyService.EffectivePrivacyPolicy effectivePolicy = privacyPolicyService.loadEffectivePolicy(policyProfileId);
        List<PromptInjectionDetectionResult> promptInjectionDetections = promptInjectionGuard.detect(content, effectivePolicy);
        SanitizationContext sanitizationContext = sanitizePrompt(mappingKey, content, policyProfileId);
        return new SanitizedUserInputResult(
                promptInjectionGuard.wrapUserInputBoundary(sanitizationContext.safePrompt()),
                promptInjectionDetections,
                sanitizationContext.privacyRiskLevel(),
                sanitizationContext.findingDetails()
        );
    }

    private void enforceUserInputLength(String content) {
        if (content != null && content.length() > MAX_USER_INPUT_CHARS) {
            throw BizException.badRequest("用户输入长度超过20000字符，系统已拒绝处理");
        }
    }

    /**
     * S3 密钥类风险前置拦截。
     * <p>
     * 先做确定性密钥/私钥/JWT 检测，再进入 Prompt 注入检测，避免高危凭证进入后续语义检测链路。
     */
    private void assertNoS3Secrets(String content) {
        if (!regexEnabled) {
            return;
        }
        RegexHardBlocker.RegexScanResult regexResult = regexHardBlocker.scan(content);
        if (regexResult.isBlocked()) {
            throw new HighRiskContentException("存在极高安全风险（检测到 API Key/Secret），请求已被拦截",
                    highRiskFindingDetails(regexResult));
        }
    }

    /**
     * 解析当前请求使用的策略档案 ID。
     * <p>
     * 请求显式传入 privacy_policy_id 时优先使用，否则使用客户端默认策略。
     */
    private Long resolvePolicyProfileId(GatewayAuthContext authContext, OpenAiChatCompletionRequest request) {
        if (request != null && request.getPrivacyPolicyId() != null) {
            Long policyProfileId = request.getPrivacyPolicyId();
            if (policyProfileId <= 0) {
                return null;
            }
            return policyProfileRepository.findById(policyProfileId)
                    .filter(profile -> authContext != null && authContext.gatewayClientId().equals(profile.getGatewayClientId()))
                    .map(profile -> policyProfileId)
                    .orElseThrow(() -> BizException.badRequest("策略配置不存在或已被删除"));
        }
        return authContext == null ? null : authContext.defaultPolicyProfileId();
    }

    /**
     * M3 脱敏处理入口：Regex 初筛 -> NER -> 策略引擎 -> 映射入库。
     */
    private SanitizationContext sanitizePrompt(String sessionUuid, String prompt, Long policyProfileId) {
        placeholderCollisionGuard.assertNoReservedPlaceholder(prompt);
        if (!privacyEnabled) {
            return new SanitizationContext(prompt, 0, 0, 0, null, List.of());
        }
        PrivacyPolicyService.EffectivePrivacyPolicy effectivePolicy = privacyPolicyService.loadEffectivePolicy(policyProfileId);
        Map<String, String> existingMapping = mappingRepository.get(sessionUuid);
        RegexHardBlocker.RegexScanResult regexResult = regexEnabled
                ? regexHardBlocker.scan(prompt)
                : new RegexHardBlocker.RegexScanResult(false, List.of());
        List<Entity> entities = shouldRunNer(prompt) ? nerService.extractEntities(prompt) : List.of();
        SanitizeResult sanitizeResult = privacyPolicyEngine.apply(prompt, regexResult, entities, existingMapping, effectivePolicy);
        mappingRepository.putAll(sessionUuid, sanitizeResult.getOriginalMapping());
        List<Map<String, Object>> findingDetails = sanitizeResult.getFindingDetails() == null
                ? List.of()
                : sanitizeResult.getFindingDetails();
        int regexHits = (int) findingDetails.stream().filter(item -> "REGEX".equals(item.get("source"))).count();
        int nerHits = (int) findingDetails.stream().filter(item -> "NER".equals(item.get("source"))).count();
        int mappingSize = sanitizeResult.getOriginalMapping() == null ? 0 : sanitizeResult.getOriginalMapping().size();
        String privacyRiskLevel = findingDetails.stream()
                .map(item -> item.get("riskLevel"))
                .map(String::valueOf)
                .reduce(null, this::higherRisk);
        return new SanitizationContext(sanitizeResult.getSafePrompt(), regexHits, nerHits, mappingSize,
                privacyRiskLevel, findingDetails);
    }

    private boolean shouldRunNer(String prompt) {
        return nerEnabled && prompt != null && prompt.length() <= SKIP_NER_CHARS;
    }

    /**
     * 使用指定流式模型执行调用并聚合完整输出。
     */
    private StreamingOutcome streamWithModel(StreamingChatModel model, List<ChatMessage> messages, Consumer<String> tokenConsumer) {
        StringBuilder fullText = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicReference<String> tokenUsageRef = new AtomicReference<>("");

        model.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (partialResponse != null && !partialResponse.isEmpty()) {
                    fullText.append(partialResponse);
                    tokenConsumer.accept(partialResponse);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse chatResponse) {
                if (fullText.isEmpty()
                        && chatResponse != null
                        && chatResponse.aiMessage() != null
                        && chatResponse.aiMessage().text() != null) {
                    fullText.append(chatResponse.aiMessage().text());
                    tokenConsumer.accept(chatResponse.aiMessage().text());
                }
                if (chatResponse != null && chatResponse.tokenUsage() != null) {
                    tokenUsageRef.set(chatResponse.tokenUsage().toString());
                }
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        try {
            boolean completed = latch.await(120, TimeUnit.SECONDS);
            if (!completed) {
                throw new BizException(504, "模型流式响应超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(500, "流式响应被中断");
        }
        if (errorRef.get() != null) {
            throw new BizException(502, "模型调用失败: " + errorRef.get().getMessage());
        }
        if (fullText.isEmpty()) {
            throw new BizException(502, "模型返回为空");
        }
        return new StreamingOutcome(fullText.toString(), tokenUsageRef.get());
    }

    private List<Map<String, Object>> highRiskFindingDetails(RegexHardBlocker.RegexScanResult regexResult) {
        if (regexResult == null || regexResult.getFindings() == null) {
            return List.of();
        }
        return regexResult.getFindings().stream()
                .filter(finding -> "API_KEY".equalsIgnoreCase(finding.getType())
                        || "JWT_TOKEN".equalsIgnoreCase(finding.getType())
                        || "AWS_ACCESS_KEY".equalsIgnoreCase(finding.getType())
                        || "PRIVATE_KEY_BLOCK".equalsIgnoreCase(finding.getType()))
                .map(finding -> {
                    Map<String, Object> detail = new java.util.LinkedHashMap<>();
                    detail.put("source", "REGEX");
                    detail.put("type", finding.getType());
                    detail.put("riskLevel", "S3");
                    detail.put("value", finding.getValue());
                    detail.put("start", finding.getStart());
                    detail.put("end", finding.getEnd());
                    return detail;
                })
                .toList();
    }

    /**
     * OpenAI 兼容链路处理结果。
     *
     * @param content 还原后的模型完整回复
     * @param mappingKey 本次请求使用的隐私映射 key
     */
    public record OpenAiProcessResult(String content,
                                      String mappingKey,
                                      List<PromptInjectionDetectionResult> promptInjectionDetections,
                                      String privacyRiskLevel,
                                      List<Map<String, Object>> findingDetails) {
    }

    private record StreamingOutcome(String text, String tokenUsageSummary) {
    }

    private record SanitizationContext(String safePrompt, int regexHitCount, int nerHitCount, int mappingSize,
                                       String privacyRiskLevel,
                                       List<Map<String, Object>> findingDetails) {
    }

    private record SanitizedMessagesResult(List<ChatMessage> messages,
                                           List<PromptInjectionDetectionResult> promptInjectionDetections,
                                           String privacyRiskLevel,
                                           List<Map<String, Object>> findingDetails,
                                           String sanitizedModelInput) {
    }

    private record SanitizedUserInputResult(String content,
                                            List<PromptInjectionDetectionResult> promptInjectionDetections,
                                            String privacyRiskLevel,
                                            List<Map<String, Object>> findingDetails) {
    }

    private String higherRisk(String current, String candidate) {
        if (candidate == null) {
            return current;
        }
        if ("S3".equals(candidate)) {
            return "S3";
        }
        if ("S2".equals(candidate) && !"S3".equals(current)) {
            return "S2";
        }
        if (current == null) {
            return candidate;
        }
        return current;
    }
}