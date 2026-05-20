package com.sustech.privacyaiproject.infrastructure.redis;
//映射存取与 TTL 管理。

import java.util.Map;

/**
 * 隐私映射仓储接口。
 * <p>
 * key: conversationId
 * value: safeValue -> rawValue
 */
public interface PrivacyMappingRepository {

    /**
     * 合并写入映射并刷新 TTL。
     */
    void putAll(String conversationId, Map<String, String> mapping);

    /**
     * 读取会话映射。
     */
    Map<String, String> get(String conversationId);

    /**
     * 删除会话映射。
     */
    void remove(String conversationId);

    /**
     * 刷新会话 TTL。
     */
    void refreshTtl(String conversationId);
}
