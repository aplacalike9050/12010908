package com.sustech.privacyaiproject.infrastructure.mapping;

import com.sustech.privacyaiproject.infrastructure.redis.PrivacyMappingRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M3 阶段临时映射存储（非 Redis）。
 * <p>
 * 说明：
 * - key: conversationId
 * - value: 脱敏占位符/合成值 -> 原始值 映射
 * - 为避免内存增长，提供简单 TTL 清理能力。
 */
@Component
@ConditionalOnProperty(name = "privacy.mapping.store", havingValue = "memory")
public class InMemoryMappingStore implements PrivacyMappingRepository {

    private static final long DEFAULT_TTL_SECONDS = 2 * 60 * 60;

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> mappingStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> expireAtStore = new ConcurrentHashMap<>();

    /**
     * 将本轮映射写入会话级存储（增量合并）。
     */
    public void putAll(String conversationId, Map<String, String> mapping) {
        if (conversationId == null || conversationId.isBlank() || mapping == null || mapping.isEmpty()) {
            return;
        }
        cleanupIfExpired(conversationId);
        mappingStore.computeIfAbsent(conversationId, k -> new ConcurrentHashMap<>()).putAll(mapping);
        expireAtStore.put(conversationId, Instant.now().getEpochSecond() + DEFAULT_TTL_SECONDS);
    }

    /**
     * 读取会话映射（只读视图）。
     */
    public Map<String, String> get(String conversationId) {
        cleanupIfExpired(conversationId);
        Map<String, String> map = mappingStore.get(conversationId);
        if (map == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * 删除会话映射。
     */
    public void remove(String conversationId) {
        mappingStore.remove(conversationId);
        expireAtStore.remove(conversationId);
    }

    @Override
    public void refreshTtl(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        if (!mappingStore.containsKey(conversationId)) {
            return;
        }
        expireAtStore.put(conversationId, Instant.now().getEpochSecond() + DEFAULT_TTL_SECONDS);
    }

    /**
     * 过期清理。
     */
    private void cleanupIfExpired(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        Long expireAt = expireAtStore.get(conversationId);
        long now = Instant.now().getEpochSecond();
        if (expireAt != null && expireAt < now) {
            remove(conversationId);
        }
    }
}
