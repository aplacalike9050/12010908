package com.sustech.privacyaiproject.service;

import com.sustech.privacyaiproject.common.exception.BizException;
import com.sustech.privacyaiproject.common.util.PasswordHashUtil;
import com.sustech.privacyaiproject.domain.entity.DeveloperAccountEntity;
import com.sustech.privacyaiproject.repository.DeveloperAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * 开发者控制台认证服务。
 * <p>
 * 注册用户默认是 DEVELOPER 身份，登录后返回轻量 Bearer token 供管理接口鉴权。
 */
@Service
@RequiredArgsConstructor
public class DeveloperAuthService {

    private final DeveloperAccountRepository developerAccountRepository;
    private final GatewayClientService gatewayClientService;

    @Value("${gateway.console.token-secret:aiprivacy-console-local-secret}")
    private String tokenSecret;

    @Transactional
    public Map<String, Object> register(String username, String password) {
        validateUsernamePassword(username, password);
        String cleanUsername = username.trim();
        if (developerAccountRepository.existsByUsername(cleanUsername)) {
            throw BizException.conflict("用户名已存在");
        }
        developerAccountRepository.syncIdSequence();
        DeveloperAccountEntity account = new DeveloperAccountEntity();
        account.setUsername(cleanUsername);
        account.setDisplayName(cleanUsername);
        account.setPasswordHash(PasswordHashUtil.hash(password));
        account.setRole("DEVELOPER");
        account.setStatus("ACTIVE");
        DeveloperAccountEntity saved = developerAccountRepository.save(account);
        GatewayClientService.CreatedGatewayClient client =
                gatewayClientService.createClient(saved.getId(), cleanUsername + " 默认应用");
        return Map.of(
                "token", issueToken(saved),
                "user", userView(saved),
                "defaultClient", Map.of(
                        "id", client.id(),
                        "clientId", client.clientId(),
                        "apiKey", client.apiKey()
                )
        );
    }

    public Map<String, Object> login(String username, String password) {
        validateUsernamePassword(username, password);
        DeveloperAccountEntity account = developerAccountRepository.findByUsername(username.trim())
                .orElseThrow(() -> BizException.unauthorized("用户名或密码错误"));
        if (!"ACTIVE".equalsIgnoreCase(account.getStatus())) {
            throw BizException.forbidden("账号已停用");
        }
        if (!PasswordHashUtil.matches(password, account.getPasswordHash())) {
            throw BizException.unauthorized("用户名或密码错误");
        }
        return Map.of("token", issueToken(account), "user", userView(account));
    }

    @Transactional
    public void changePassword(DeveloperAccountEntity account, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw BizException.badRequest("新密码至少需要6位");
        }
        if (!PasswordHashUtil.matches(oldPassword, account.getPasswordHash())) {
            throw BizException.unauthorized("当前密码错误");
        }
        account.setPasswordHash(PasswordHashUtil.hash(newPassword));
        developerAccountRepository.save(account);
    }

    public DeveloperAccountEntity requireDeveloper(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw BizException.unauthorized("请先登录开发者控制台");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":");
            if (parts.length != 4 || !signature(parts[0], parts[1], parts[2]).equals(parts[3])) {
                throw BizException.unauthorized("登录凭证无效");
            }
            Long id = Long.valueOf(parts[0]);
            DeveloperAccountEntity account = developerAccountRepository.findById(id)
                    .orElseThrow(() -> BizException.unauthorized("登录账号不存在"));
            if (!"ACTIVE".equalsIgnoreCase(account.getStatus())) {
                throw BizException.forbidden("账号已停用");
            }
            return account;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BizException.unauthorized("登录凭证无效");
        }
    }

    public Map<String, Object> userView(DeveloperAccountEntity account) {
        return Map.of(
                "id", account.getId(),
                "username", account.getUsername(),
                "displayName", account.getDisplayName() == null ? account.getUsername() : account.getDisplayName(),
                "role", account.getRole(),
                "status", account.getStatus()
        );
    }

    private String issueToken(DeveloperAccountEntity account) {
        String id = String.valueOf(account.getId());
        String username = account.getUsername();
        String issuedAt = String.valueOf(Instant.now().getEpochSecond());
        String raw = id + ":" + username + ":" + issuedAt + ":" + signature(id, username, issuedAt);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String signature(String id, String username, String issuedAt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((id + ":" + username + ":" + issuedAt + ":" + tokenSecret)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("签名生成失败", ex);
        }
    }

    private void validateUsernamePassword(String username, String password) {
        if (username == null || username.trim().length() < 3) {
            throw BizException.badRequest("用户名至少需要3位");
        }
        if (password == null || password.length() < 6) {
            throw BizException.badRequest("密码至少需要6位");
        }
    }
}
