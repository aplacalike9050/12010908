package com.sustech.privacyaiproject.service;

import com.sustech.privacyaiproject.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 模型密钥加解密服务。
 * <p>
 * 当前服务负责解密控制台写入的自定义模型密钥，密文格式为 aesgcm:base64(iv):base64(cipherText)。
 */
@Service
public class CredentialCryptoService {

    private static final String PREFIX = "aesgcm:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${gateway.credential.master-key:}")
    private String masterKey;

    /**
     * 解密模型 API Key。
     *
     * @param encryptedValue 数据库中保存的密文
     * @return 明文 API Key
     */
    public String decryptApiKey(String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            throw BizException.badRequest("模型密钥为空");
        }
        if (!encryptedValue.startsWith(PREFIX)) {
            throw BizException.badRequest("模型密钥密文格式不正确");
        }
        if (masterKey == null || masterKey.isBlank()) {
            throw new BizException(500, "未配置模型密钥主密钥 MODEL_CREDENTIAL_MASTER_KEY");
        }
        try {
            String[] parts = encryptedValue.split(":");
            if (parts.length != 3) {
                throw BizException.badRequest("模型密钥密文格式不正确");
            }
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] cipherText = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, buildAesKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(500, "模型密钥解密失败");
        }
    }

    /**
     * 加密模型 API Key，供控制台新增模型密钥时使用。
     *
     * @param rawApiKey 明文 API Key
     * @return aesgcm 格式密文
     */
    public String encryptApiKey(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            throw BizException.badRequest("模型API Key不能为空");
        }
        if (masterKey == null || masterKey.isBlank()) {
            throw new BizException(500, "未配置模型密钥主密钥 MODEL_CREDENTIAL_MASTER_KEY");
        }
        try {
            byte[] iv = new byte[12];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, buildAesKey(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(rawApiKey.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception ex) {
            throw new BizException(500, "模型密钥加密失败");
        }
    }

    /**
     * 基于配置主密钥派生 256 位 AES Key。
     */
    private SecretKeySpec buildAesKey() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] key = digest.digest(masterKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }
}
