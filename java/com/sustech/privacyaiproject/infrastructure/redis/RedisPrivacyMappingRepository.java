package com.sustech.privacyaiproject.infrastructure.redis;

import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 版隐私映射仓储。
 * 使用 Hash 结构存储 safeValue -> rawValue。
 */
@Repository
@ConditionalOnProperty(name = "privacy.mapping.store", havingValue = "redis", matchIfMissing = true)
@Slf4j
public class RedisPrivacyMappingRepository implements PrivacyMappingRepository {

    private final StringRedisTemplate redisTemplate;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> fallbackStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> fallbackExpireAt = new ConcurrentHashMap<>();

    @Value("${privacy.mapping.key-prefix:privacy:map:}")
    private String keyPrefix;
    @Value("${privacy.mapping.ttl-seconds:3600}")
    private long ttlSeconds;
    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;
    @Value("${spring.data.redis.port:6379}")
    private int redisPort;
    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;
    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    public RedisPrivacyMappingRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void checkRedisConnectivity() {
        try {
            String pong = redisTemplate.getConnectionFactory() != null
                    ? redisTemplate.getConnectionFactory().getConnection().ping()
                    : null;
            log.info("M4 Redis连通性检查成功 host={}, port={}, db={}, hasPassword={}, ping={}",
                    redisHost, redisPort, redisDatabase, redisPassword != null && !redisPassword.isBlank(), pong);
        } catch (Exception ex) {
            log.warn("M4 Redis连通性检查失败 host={}, port={}, db={}, hasPassword={}, exceptionType={}, rootCauseType={}, rootCauseMessage={}",
                    redisHost, redisPort, redisDatabase, redisPassword != null && !redisPassword.isBlank(),
                    ex.getClass().getName(), rootCauseType(ex), rootCauseMessage(ex));
        }
    }

    @Override
    public void putAll(String conversationId, Map<String, String> mapping) {
        if (conversationId == null || conversationId.isBlank() || mapping == null || mapping.isEmpty()) {
            return;
        }
        String key = buildKey(conversationId);
        try {
            redisTemplate.opsForHash().putAll(key, new LinkedHashMap<>(mapping));
            redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
            Long ttl = redisTemplate.getExpire(key);
            Long size = redisTemplate.opsForHash().size(key);
            // 答辩展示日志：确认 key 已命中且 TTL 生效
            log.info("M4 Redis映射写入 key={}, hashSize={}, ttlSeconds={}", key, size, ttl);
        } catch (Exception ex) {
            log.warn("M4 Redis写入失败 key={}, host={}, port={}, db={}, exceptionType={}, rootCauseType={}, rootCauseMessage={}",
                    key, redisHost, redisPort, redisDatabase, ex.getClass().getName(), rootCauseType(ex), rootCauseMessage(ex));
            if (isRedisUnavailable(ex)) {
                putAllFallback(conversationId, mapping);
                log.warn("Redis不可用，映射已降级写入内存 key={}", key);
                return;
            }
            throw ex;
        }
    }

    @Override
    public Map<String, String> get(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Collections.emptyMap();
        }
        String key = buildKey(conversationId);
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            if (entries == null || entries.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, String> output = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                output.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            return output;
        } catch (Exception ex) {
            log.warn("M4 Redis读取失败 key={}, host={}, port={}, db={}, exceptionType={}, rootCauseType={}, rootCauseMessage={}",
                    key, redisHost, redisPort, redisDatabase, ex.getClass().getName(), rootCauseType(ex), rootCauseMessage(ex));
            if (isRedisUnavailable(ex)) {
                log.warn("Redis不可用，映射降级为内存读取 key={}", key);
                return getFallback(conversationId);
            }
            throw ex;
        }
    }

    @Override
    public void remove(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        String key = buildKey(conversationId);
        try {
            redisTemplate.delete(key);
        } catch (Exception ex) {
            if (isRedisUnavailable(ex)) {
                removeFallback(conversationId);
                log.warn("Redis不可用，映射已降级内存删除 key={}", key);
                return;
            }
            throw ex;
        }
    }

    @Override
    public void refreshTtl(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        String key = buildKey(conversationId);
        try {
            redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
        } catch (Exception ex) {
            if (isRedisUnavailable(ex)) {
                refreshFallbackTtl(conversationId);
                log.warn("Redis不可用，映射已降级内存刷新TTL key={}", key);
                return;
            }
            throw ex;
        }
    }

    private String buildKey(String conversationId) {
        return keyPrefix + conversationId;
    }

    private boolean isRedisUnavailable(Exception ex) {
        if (ex instanceof org.springframework.data.redis.RedisConnectionFailureException) {
            return true;
        }
        if (ex instanceof DataAccessException dataAccessException) {
            Throwable root = rootCause(dataAccessException);
            return root instanceof java.net.ConnectException;
        }
        Throwable root = rootCause(ex);
        return root instanceof java.net.ConnectException;
    }

    private void putAllFallback(String conversationId, Map<String, String> mapping) {
        cleanupFallbackIfExpired(conversationId);
        fallbackStore.computeIfAbsent(conversationId, k -> new ConcurrentHashMap<>()).putAll(mapping);
        fallbackExpireAt.put(conversationId, System.currentTimeMillis() + Duration.ofSeconds(ttlSeconds).toMillis());
    }

    private Map<String, String> getFallback(String conversationId) {
        cleanupFallbackIfExpired(conversationId);
        Map<String, String> map = fallbackStore.get(conversationId);
        if (map == null || map.isEmpty()) {
            return Collections.emptyMap();
        }
        return new LinkedHashMap<>(map);
    }

    private void removeFallback(String conversationId) {
        fallbackStore.remove(conversationId);
        fallbackExpireAt.remove(conversationId);
    }

    private void refreshFallbackTtl(String conversationId) {
        if (fallbackStore.containsKey(conversationId)) {
            fallbackExpireAt.put(conversationId, System.currentTimeMillis() + Duration.ofSeconds(ttlSeconds).toMillis());
        }
    }

    private void cleanupFallbackIfExpired(String conversationId) {
        Long expireAt = fallbackExpireAt.get(conversationId);
        if (expireAt != null && expireAt < System.currentTimeMillis()) {
            removeFallback(conversationId);
        }
    }

    private String rootCauseType(Throwable throwable) {
        Throwable root = rootCause(throwable);
        return root == null ? "" : root.getClass().getName();
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable root = rootCause(throwable);
        return root == null || root.getMessage() == null ? "" : root.getMessage();
    }

    private Throwable rootCause(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
