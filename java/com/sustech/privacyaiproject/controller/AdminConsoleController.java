package com.sustech.privacyaiproject.controller;

import com.sustech.privacyaiproject.common.exception.BizException;
import com.sustech.privacyaiproject.common.result.Result;
import com.sustech.privacyaiproject.domain.entity.DeveloperAccountEntity;
import com.sustech.privacyaiproject.domain.entity.GatewayClientEntity;
import com.sustech.privacyaiproject.domain.entity.ModelCredentialEntity;
import com.sustech.privacyaiproject.domain.entity.PrivacyAuditEventEntity;
import com.sustech.privacyaiproject.domain.entity.PrivacyPolicyProfileEntity;
import com.sustech.privacyaiproject.domain.entity.PrivacyPolicyRuleEntity;
import com.sustech.privacyaiproject.repository.GatewayClientRepository;
import com.sustech.privacyaiproject.repository.ModelCredentialRepository;
import com.sustech.privacyaiproject.repository.PrivacyAuditEventRepository;
import com.sustech.privacyaiproject.repository.PrivacyPolicyProfileRepository;
import com.sustech.privacyaiproject.repository.PrivacyPolicyRuleRepository;
import com.sustech.privacyaiproject.service.CredentialCryptoService;
import com.sustech.privacyaiproject.service.DeveloperAuthService;
import com.sustech.privacyaiproject.service.GatewayClientService;
import com.sustech.privacyaiproject.service.ModelResilienceService;
import com.sustech.privacyaiproject.service.PrivacyPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 开发者控制台管理接口。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminConsoleController {

    private static final List<PrivacyTypeView> BUILTIN_PRIVACY_TYPES = List.of(
            new PrivacyTypeView(PrivacyPolicyService.PROMPT_INJECTION_BLACKLIST, "黑名单特征词", "PROMPT_INJECTION", "BLOCK", false),
            new PrivacyTypeView(PrivacyPolicyService.PROMPT_INJECTION_MODEL, "分类模型计算", "PROMPT_INJECTION", "BLOCK", false),
            new PrivacyTypeView("PER", "姓名/人名", "S1", "SYNTHESIS", false),
            new PrivacyTypeView("LOC", "地点", "S2", "MASK", false),
            new PrivacyTypeView("ORG", "组织机构", "S2", "MASK", false),
            new PrivacyTypeView("PHONE", "手机号", "S2", "MASK", false),
            new PrivacyTypeView("EMAIL", "邮箱", "S2", "MASK", false),
            new PrivacyTypeView("ID_CARD", "身份证号", "S2", "MASK", false),
            new PrivacyTypeView("BANK_CARD", "银行卡号", "S2", "MASK", false),
            new PrivacyTypeView("ADDRESS", "详细地址", "S2", "MASK", false),
            new PrivacyTypeView("IPV4", "IPv4 地址", "S2", "MASK", false),
            new PrivacyTypeView("MAC", "MAC 地址", "S2", "MASK", false),
            new PrivacyTypeView("PASSPORT", "护照号", "S2", "MASK", false),
            new PrivacyTypeView("SSN", "社会安全号", "S2", "MASK", false),
            new PrivacyTypeView("API_KEY", "API Key", "S3", "BLOCK", true),
            new PrivacyTypeView("JWT_TOKEN", "JWT Token", "S3", "BLOCK", true),
            new PrivacyTypeView("AWS_ACCESS_KEY", "AWS Access Key", "S3", "BLOCK", true),
            new PrivacyTypeView("PRIVATE_KEY_BLOCK", "私钥块", "S3", "BLOCK", true)
    );

    private final DeveloperAuthService developerAuthService;
    private final GatewayClientService gatewayClientService;
    private final GatewayClientRepository gatewayClientRepository;
    private final PrivacyAuditEventRepository privacyAuditEventRepository;
    private final PrivacyPolicyProfileRepository policyProfileRepository;
    private final PrivacyPolicyRuleRepository policyRuleRepository;
    private final ModelCredentialRepository modelCredentialRepository;
    private final CredentialCryptoService credentialCryptoService;
    private final ModelResilienceService modelResilienceService;

    @GetMapping("/metrics/summary")
    public Result<Map<String, Object>> metricsSummary(@RequestHeader("Authorization") String authorization) {
        requireDeveloper(authorization);
        long total = privacyAuditEventRepository.count();
        long success = privacyAuditEventRepository.countBySuccess(true);
        long blocked = privacyAuditEventRepository.countByBlocked(true);
        return Result.success(Map.of(
                "totalCalls", total,
                "successCalls", success,
                "failedCalls", Math.max(0, total - success),
                "blockedCalls", blocked,
                "resilience", modelResilienceService.snapshot()
        ));
    }

    @GetMapping("/audit-events")
    public Result<List<Map<String, Object>>> auditEvents(@RequestHeader("Authorization") String authorization,
                                                        @RequestParam(required = false) String riskLevel,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(required = false) String keyword) {
        DeveloperAccountEntity developer = requireDeveloper(authorization);
        Set<Long> clientIds = ownedClientIds(developer);
        List<Map<String, Object>> rows = privacyAuditEventRepository
                .findAll(Sort.by(Sort.Direction.DESC, "createTime"))
                .stream()
                .filter(event -> event.getGatewayClientId() == null || clientIds.contains(event.getGatewayClientId()))
                .filter(event -> matchesRiskLevel(event, riskLevel))
                .filter(event -> matchesStatus(event, status))
                .filter(event -> matchesKeyword(event, keyword))
                .limit(100)
                .map(this::auditView)
                .toList();
        return Result.success(rows);
    }

    @GetMapping("/audit-events/{id}")
    public Result<PrivacyAuditEventEntity> auditEvent(@RequestHeader("Authorization") String authorization,
                                                      @PathVariable Long id) {
        DeveloperAccountEntity developer = requireDeveloper(authorization);
        PrivacyAuditEventEntity event = privacyAuditEventRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("审计事件不存在"));
        if (event.getGatewayClientId() != null && !ownedClientIds(developer).contains(event.getGatewayClientId())) {
            throw BizException.forbidden("无权查看该审计事件");
        }
        return Result.success(event);
    }

    @GetMapping("/policies")
    public Result<List<Map<String, Object>>> policies(@RequestHeader("Authorization") String authorization) {
        DeveloperAccountEntity developer = requireDeveloper(authorization);
        List<Map<String, Object>> output = new ArrayList<>();
        output.add(systemDefaultPolicyView());
        for (GatewayClientEntity client : ownedClients(developer)) {
            List<PrivacyPolicyProfileEntity> profiles = policyProfileRepository
                    .findByGatewayClientIdOrderByCreateTimeDesc(client.getId());
            profiles.stream()
                    .filter(profile -> !Boolean.TRUE.equals(profile.getDefaultProfile()))
                    .forEach(profile -> output.add(policyView(profile)));
        }
        return Result.success(output);
    }

    @PutMapping("/policies/{profileId}/rules")
    public Result<Map<String, Object>> updatePolicyRules(@RequestHeader("Authorization") String authorization,
                                                         @PathVariable Long profileId,
                                                         @RequestBody PolicyRulesRequest request) {
        DeveloperAccountEntity developer = requireDeveloper(authorization);
        List<GatewayClientEntity> clients = ownedClients(developer);
        if (clients.isEmpty()) {
            throw BizException.badRequest("请先创建客户端应用");
        }
        List<PrivacyPolicyProfileEntity> currentProfiles = clients.stream()
                .flatMap(client -> policyProfileRepository.findByGatewayClientIdOrderByCreateTimeDesc(client.getId()).stream())
                .toList();
        if (currentProfiles.size() >= 10) {
            throw BizException.badRequest("每个开发者最多保留10个策略配置，请先删除已有配置后再保存");
        }
        PrivacyPolicyProfileEntity sourceProfile = null;
        GatewayClientEntity targetClient = clients.get(0);
        if (profileId != null && profileId > 0) {
            sourceProfile = policyProfileRepository.findById(profileId)
                    .orElseThrow(() -> BizException.notFound("策略档案不存在"));
            ensureOwnedClient(developer, sourceProfile.getGatewayClientId());
            targetClient = gatewayClientRepository.findById(sourceProfile.getGatewayClientId()).orElse(targetClient);
        }
        PrivacyPolicyProfileEntity profile = new PrivacyPolicyProfileEntity();
        profile.setGatewayClientId(targetClient.getId());
        profile.setProfileName(resolveNewPolicyName(sourceProfile, request));
        profile.setVersion(nextPolicyVersion(targetClient.getId()));
        profile.setDefaultProfile(false);
        profile.setStatus("ACTIVE");
        profile.setDescription("由控制台保存生成的新策略配置");
        policyProfileRepository.syncIdSequence();
        PrivacyPolicyProfileEntity savedProfile = policyProfileRepository.save(profile);
        for (RuleRequest ruleRequest : request.rules()) {
            PrivacyPolicyRuleEntity rule = newPolicyRule(savedProfile.getId(), ruleRequest.privacyType());
            if (Boolean.TRUE.equals(rule.getForced())) {
                rule.setEnabled(true);
                rule.setAction("BLOCK");
            } else if (isPromptInjectionRule(rule.getPrivacyType())) {
                rule.setEnabled(true);
                rule.setAction(promptInjectionAction(ruleRequest.action()));
            } else {
                rule.setEnabled(ruleRequest.enabled());
                rule.setAction(ruleRequest.action());
            }
            policyRuleRepository.save(rule);
        }
        return Result.success(policyView(savedProfile));
    }

    @PutMapping("/policies/{profileId}")
    public Result<Map<String, Object>> updatePolicy(@RequestHeader("Authorization") String authorization,
                                                    @PathVariable Long profileId,
                                                    @RequestBody PolicyRulesRequest request) {
        DeveloperAccountEntity developer = requireDeveloper(authorization);
        if (profileId == null || profileId <= 0) {
            throw BizException.badRequest("系统默认配置不可更新");
        }
        PrivacyPolicyProfileEntity profile = policyProfileRepository.findById(profileId)
                .orElseThrow(() -> BizException.notFound("策略档案不存在"));
        ensureOwnedClient(developer, profile.getGatewayClientId());
        if (Boolean.TRUE.equals(profile.getDefaultProfile())) {
            throw BizException.badRequest("系统默认配置不可更新");
        }
        if (request.profileName() != null && !request.profileName().isBlank()) {
            profile.setProfileName(request.profileName().trim());
            policyProfileRepository.save(profile);
        }
        for (RuleRequest ruleRequest : request.rules()) {
            PrivacyPolicyRuleEntity rule = policyRuleRepository
                    .findByPolicyProfileIdAndPrivacyType(profileId, ruleRequest.privacyType())
                    .orElseGet(() -> newPolicyRule(profileId, ruleRequest.privacyType()));
            if (Boolean.TRUE.equals(rule.getForced())) {
                rule.setEnabled(true);
                rule.setAction("BLOCK");
            } else if (isPromptInjectionRule(rule.getPrivacyType())) {
                rule.setEnabled(true);
                rule.setAction(promptInjectionAction(ruleRequest.action()));
            } else {
                rule.setEnabled(Boolean.TRUE.equals(ruleRequest.enabled()));
            }
            policyRuleRepository.save(rule);
        }
        return Result.success(policyView(profile));
    }

    @DeleteMapping("/policies/{profileId}")
    public Result<String> deletePolicy(@RequestHeader("Authorization") String authorization,
                                       @PathVariable Long profileId) {
        DeveloperAccountEntity developer = requireDeveloper(authorization);
        if (profileId == null || profileId <= 0) {
            throw BizException.badRequest("系统默认配置不可删除");
        }
        PrivacyPolicyProfileEntity profile = policyProfileRepository.findById(profileId)
                .orElseThrow(() -> BizException.notFound("策略档案不存在"));
        ensureOwnedClient(developer, profile.getGatewayClientId());
        if (Boolean.TRUE.equals(profile.getDefaultProfile())) {
            throw BizException.badRequest("系统默认配置不可删除");
        }
        policyProfileRepository.delete(profile);
        return Result.success("删除成功");
    }

    @GetMapping("/clients")
    public Result<List<Map<String, Object>>> clients(@RequestHeader("Authorization") String authorization) {
        DeveloperAccountEntity developer = requireDeveloper(authorization);
        return Result.success(ownedClients(developer).stream().map(this::clientView).toList());
    }

    @PostMapping("/clients")
    public Result<Map<String, Object>> createClient(@RequestHeader("Authorization") String authorization,
                                                    @RequestBody CreateClientRequest request) {
        DeveloperAccountEntity developer = requireDeveloper(authorization);
        GatewayClientService.CreatedGatewayClient created =
                gatewayClientService.createClient(developer.getId(), request.clientName());
        return Result.success(Map.of("id", created.id(), "clientId", created.clientId(), "apiKey", created.apiKey()));
    }

    @GetMapping("/model-credentials")
    public Result<List<Map<String, Object>>> modelCredentials(@RequestHeader("Authorization") String authorization) {
        DeveloperAccountEntity developer = requireDeveloper(authorization);
        List<Map<String, Object>> rows = ownedClients(developer).stream()
                .flatMap(client -> modelCredentialRepository.findByGatewayClientIdOrderByCreateTimeDesc(client.getId()).stream())
                .sorted(Comparator.comparing(ModelCredentialEntity::getCreateTime).reversed())
                .map(this::modelCredentialView)
                .toList();
        return Result.success(rows);
    }

    @PostMapping("/model-credentials")
    public Result<Map<String, Object>> createModelCredential(@RequestHeader("Authorization") String authorization,
                                                            @RequestBody ModelCredentialRequest request) {
        DeveloperAccountEntity developer = requireDeveloper(authorization);
        ensureOwnedClient(developer, request.gatewayClientId());
        ModelCredentialEntity entity = new ModelCredentialEntity();
        entity.setGatewayClientId(request.gatewayClientId());
        entity.setCredentialName(request.credentialName());
        entity.setProvider(request.provider());
        entity.setBaseUrl(request.baseUrl());
        entity.setModelName(request.modelName());
        entity.setEncryptedApiKey(credentialCryptoService.encryptApiKey(request.apiKey()));
        entity.setStatus("ACTIVE");
        return Result.success(modelCredentialView(modelCredentialRepository.save(entity)));
    }

    @PostMapping("/model-credentials/{id}/test")
    public Result<Map<String, Object>> testModelCredential(@RequestHeader("Authorization") String authorization,
                                                          @PathVariable Long id) {
        DeveloperAccountEntity developer = requireDeveloper(authorization);
        ModelCredentialEntity credential = modelCredentialRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("模型密钥不存在"));
        ensureOwnedClient(developer, credential.getGatewayClientId());
        credentialCryptoService.decryptApiKey(credential.getEncryptedApiKey());
        return Result.success(Map.of(
                "status", "OK",
                "message", "模型密钥配置可解密，Base URL 和模型名已就绪",
                "baseUrl", credential.getBaseUrl(),
                "modelName", credential.getModelName()
        ));
    }

    @DeleteMapping("/model-credentials/{id}")
    public Result<String> deleteModelCredential(@RequestHeader("Authorization") String authorization,
                                                @PathVariable Long id) {
        DeveloperAccountEntity developer = requireDeveloper(authorization);
        ModelCredentialEntity credential = modelCredentialRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("模型密钥不存在"));
        ensureOwnedClient(developer, credential.getGatewayClientId());
        modelCredentialRepository.delete(credential);
        return Result.success("删除成功");
    }

    @GetMapping("/docs/openai-compatible")
    public Result<Map<String, Object>> docs(@RequestHeader("Authorization") String authorization) {
        requireDeveloper(authorization);
        return Result.success(Map.of(
                "endpoint", "POST /v1/chat/completions",
                "auth", "Authorization: Bearer <gatewayApiKey>",
                "credentialModes", List.of("SYSTEM_DEFAULT", "CLIENT_PROVIDED"),
                "privacyTypes", BUILTIN_PRIVACY_TYPES
        ));
    }

    @GetMapping("/system-models")
    public Result<List<Map<String, Object>>> systemModels(@RequestHeader("Authorization") String authorization) {
        requireDeveloper(authorization);
        return Result.success(List.of(
                Map.of("label", "DeepSeek", "value", "deepseek", "description", "使用后端 ai.deepseek 配置的系统默认密钥"),
                Map.of("label", "Gemini", "value", "gemini", "description", "使用后端 ai.gemini 配置的系统默认密钥"),
                Map.of("label", "ChatGPT / OpenAI", "value", "chatgpt", "description", "使用后端 ai.openai 配置的系统默认密钥")
        ));
    }

    private DeveloperAccountEntity requireDeveloper(String authorization) {
        return developerAuthService.requireDeveloper(authorization);
    }

    private List<GatewayClientEntity> ownedClients(DeveloperAccountEntity developer) {
        return gatewayClientRepository.findByOwnerDeveloperIdOrderByCreateTimeDesc(developer.getId());
    }

    private Set<Long> ownedClientIds(DeveloperAccountEntity developer) {
        return ownedClients(developer).stream().map(GatewayClientEntity::getId).collect(java.util.stream.Collectors.toSet());
    }

    private void ensureOwnedClient(DeveloperAccountEntity developer, Long gatewayClientId) {
        if (gatewayClientId == null || !ownedClientIds(developer).contains(gatewayClientId)) {
            throw BizException.forbidden("无权操作该客户端资源");
        }
    }

    private boolean matchesStatus(PrivacyAuditEventEntity event, String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return true;
        }
        return switch (status.toUpperCase()) {
            case "SUCCESS" -> Boolean.TRUE.equals(event.getSuccess()) && !Boolean.TRUE.equals(event.getBlocked());
            case "BLOCKED" -> Boolean.TRUE.equals(event.getBlocked());
            case "FAILED" -> !Boolean.TRUE.equals(event.getSuccess()) && !Boolean.TRUE.equals(event.getBlocked());
            default -> true;
        };
    }

    private boolean matchesRiskLevel(PrivacyAuditEventEntity event, String riskLevel) {
        if (riskLevel == null || riskLevel.isBlank() || "ALL".equalsIgnoreCase(riskLevel)) {
            return true;
        }
        if ("PROMPT_INJECTION".equalsIgnoreCase(riskLevel)) {
            return Boolean.TRUE.equals(event.getPromptInjectionDetected())
                    || "PROMPT_INJECTION".equalsIgnoreCase(event.getRiskLevel());
        }
        return riskLevel.equalsIgnoreCase(event.getPrivacyRiskLevel())
                || riskLevel.equalsIgnoreCase(event.getRiskLevel());
    }

    private boolean matchesKeyword(PrivacyAuditEventEntity event, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String value = keyword.trim().toLowerCase();
        return contains(event.getRequestId(), value)
                || contains(event.getClientId(), value)
                || contains(event.getModelName(), value);
    }

    private boolean contains(String source, String value) {
        return source != null && source.toLowerCase().contains(value);
    }

    private Map<String, Object> auditView(PrivacyAuditEventEntity event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", event.getId());
        row.put("requestId", event.getRequestId());
        row.put("clientId", event.getClientId());
        row.put("modelName", event.getModelName());
        row.put("riskLevel", event.getRiskLevel());
        row.put("promptInjectionDetected", Boolean.TRUE.equals(event.getPromptInjectionDetected())
                || "PROMPT_INJECTION".equalsIgnoreCase(event.getRiskLevel()));
        row.put("privacyRiskLevel", event.getPrivacyRiskLevel() == null ? legacyPrivacyRiskLevel(event.getRiskLevel()) : event.getPrivacyRiskLevel());
        row.put("findingFields", findingFields(event.getFindingDetailJson()));
        row.put("blocked", event.getBlocked());
        row.put("success", event.getSuccess());
        row.put("statusCode", event.getStatusCode());
        row.put("errorMessage", event.getErrorMessage());
        row.put("latencyMs", event.getLatencyMs());
        row.put("createTime", event.getCreateTime());
        return row;
    }

    private String legacyPrivacyRiskLevel(String riskLevel) {
        if ("S1".equalsIgnoreCase(riskLevel) || "S2".equalsIgnoreCase(riskLevel) || "S3".equalsIgnoreCase(riskLevel)) {
            return riskLevel.toUpperCase();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> findingFields(Map<String, Object> findingDetailJson) {
        if (findingDetailJson == null || findingDetailJson.isEmpty()) {
            return List.of();
        }
        Object fields = findingDetailJson.get("fields");
        if (!(fields instanceof List<?> fieldList)) {
            return List.of();
        }
        return fieldList.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .map(item -> String.valueOf(item.get("value")))
                .filter(value -> value != null && !value.isBlank() && !"null".equalsIgnoreCase(value))
                .distinct()
                .toList();
    }

    private Map<String, Object> clientView(GatewayClientEntity client) {
        return Map.of(
                "id", client.getId(),
                "clientId", client.getClientId(),
                "clientName", client.getClientName(),
                "status", client.getStatus(),
                "rateLimitPerMinute", client.getRateLimitPerMinute(),
                "concurrentLimit", client.getConcurrentLimit(),
                "defaultPolicyProfileId", validClientPolicyProfileId(client) == null ? "" : validClientPolicyProfileId(client)
        );
    }

    private Long validClientPolicyProfileId(GatewayClientEntity client) {
        Long policyProfileId = client.getDefaultPolicyProfileId();
        if (policyProfileId == null || policyProfileId <= 0) {
            return null;
        }
        return policyProfileRepository.findById(policyProfileId)
                .filter(profile -> client.getId().equals(profile.getGatewayClientId()))
                .map(profile -> policyProfileId)
                .orElse(null);
    }

    private Map<String, Object> modelCredentialView(ModelCredentialEntity credential) {
        return Map.of(
                "id", credential.getId(),
                "gatewayClientId", credential.getGatewayClientId(),
                "credentialName", credential.getCredentialName(),
                "provider", credential.getProvider(),
                "baseUrl", credential.getBaseUrl() == null ? "" : credential.getBaseUrl(),
                "modelName", credential.getModelName(),
                "status", credential.getStatus(),
                "createTime", credential.getCreateTime()
        );
    }

    private Map<String, Object> systemDefaultPolicyView() {
        List<Map<String, Object>> rules = BUILTIN_PRIVACY_TYPES.stream()
                .map(type -> Map.<String, Object>of(
                        "id", 0,
                        "privacyType", type.type(),
                        "displayName", type.displayName(),
                        "riskLevel", type.riskLevel(),
                        "action", type.defaultAction(),
                        "enabled", true,
                        "forced", type.forced()
                ))
                .toList();
        return Map.of(
                "id", 0,
                "gatewayClientId", "",
                "profileName", "系统默认配置",
                "version", 1,
                "defaultProfile", true,
                "systemDefault", true,
                "status", "ACTIVE",
                "rules", rules
        );
    }

    private String resolveNewPolicyName(PrivacyPolicyProfileEntity sourceProfile, PolicyRulesRequest request) {
        if (request.profileName() != null && !request.profileName().isBlank()) {
            return request.profileName().trim();
        }
        String base = sourceProfile == null ? "基于系统默认配置" : sourceProfile.getProfileName();
        return base + " - 新配置";
    }

    private int nextPolicyVersion(Long gatewayClientId) {
        return policyProfileRepository.findByGatewayClientIdOrderByCreateTimeDesc(gatewayClientId)
                .stream()
                .map(PrivacyPolicyProfileEntity::getVersion)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private PrivacyPolicyRuleEntity newPolicyRule(Long profileId, String privacyType) {
        PrivacyTypeView type = BUILTIN_PRIVACY_TYPES.stream()
                .filter(item -> item.type().equalsIgnoreCase(privacyType))
                .findFirst()
                .orElse(new PrivacyTypeView(privacyType, privacyType, "S2", "MASK", false));
        PrivacyPolicyRuleEntity rule = new PrivacyPolicyRuleEntity();
        rule.setPolicyProfileId(profileId);
        rule.setPrivacyType(type.type());
        rule.setRiskLevel(type.riskLevel());
        rule.setAction(type.defaultAction());
        rule.setEnabled(true);
        rule.setForced(type.forced());
        rule.setRuleSource(type.forced() ? "SYSTEM" : "USER");
        return rule;
    }

    private boolean isPromptInjectionRule(String privacyType) {
        return PrivacyPolicyService.PROMPT_INJECTION_BLACKLIST.equalsIgnoreCase(privacyType)
                || PrivacyPolicyService.PROMPT_INJECTION_MODEL.equalsIgnoreCase(privacyType);
    }

    private String promptInjectionAction(String action) {
        return "RECORD".equalsIgnoreCase(action) ? "RECORD" : "BLOCK";
    }

    private Map<String, Object> policyView(PrivacyPolicyProfileEntity profile) {
        Map<String, PrivacyPolicyRuleEntity> existing = new LinkedHashMap<>();
        for (PrivacyPolicyRuleEntity rule : policyRuleRepository.findByPolicyProfileId(profile.getId())) {
            existing.put(rule.getPrivacyType(), rule);
        }
        List<Map<String, Object>> rules = BUILTIN_PRIVACY_TYPES.stream().map(type -> {
            PrivacyPolicyRuleEntity rule = existing.get(type.type());
            if (rule == null) {
                rule = policyRuleRepository.save(newPolicyRule(profile.getId(), type.type()));
            }
            return Map.<String, Object>of(
                    "id", rule.getId(),
                    "privacyType", rule.getPrivacyType(),
                    "displayName", type.displayName(),
                    "riskLevel", type.riskLevel(),
                    "action", rule.getAction(),
                    "enabled", rule.getEnabled(),
                    "forced", rule.getForced()
            );
        }).toList();
        return Map.of(
                "id", profile.getId(),
                "gatewayClientId", profile.getGatewayClientId(),
                "profileName", profile.getProfileName(),
                "version", profile.getVersion(),
                "defaultProfile", profile.getDefaultProfile(),
                "status", profile.getStatus(),
                "rules", rules
        );
    }

    public record PrivacyTypeView(String type, String displayName, String riskLevel, String defaultAction, boolean forced) {
    }

    public record PolicyRulesRequest(String profileName, List<RuleRequest> rules) {
    }

    public record RuleRequest(String privacyType, String action, Boolean enabled) {
    }

    public record CreateClientRequest(String clientName) {
    }

    public record ModelCredentialRequest(Long gatewayClientId, String credentialName, String provider,
                                         String baseUrl, String modelName, String apiKey) {
    }
}
