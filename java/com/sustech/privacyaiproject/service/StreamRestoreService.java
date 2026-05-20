package com.sustech.privacyaiproject.service;

import com.sustech.privacyaiproject.infrastructure.redis.PrivacyMappingRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流式还原服务。
 * <p>
 * 通过滑动缓冲区避免占位符被分帧截断时的错误还原。
 */
@Service
public class StreamRestoreService {

    private final PrivacyMappingRepository mappingRepository;
    private final RestoreService restoreService;
    private final ConcurrentHashMap<String, StringBuilder> bufferStore = new ConcurrentHashMap<>();

    public StreamRestoreService(PrivacyMappingRepository mappingRepository, RestoreService restoreService) {
        this.mappingRepository = mappingRepository;
        this.restoreService = restoreService;
    }

    /**
     * 输入单帧 token，输出当前可安全下发的还原文本。
     */
    public String feed(String conversationId, String delta) {
        if (conversationId == null || conversationId.isBlank()) {
            return delta == null ? "" : delta;
        }
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        StringBuilder buffer = bufferStore.computeIfAbsent(conversationId, k -> new StringBuilder());
        buffer.append(delta);

        int safeCut = calculateSafeCut(buffer);
        if (safeCut <= 0) {
            return "";
        }
        String ready = buffer.substring(0, safeCut);
        buffer.delete(0, safeCut);
        Map<String, String> mapping = mappingRepository.get(conversationId);
        return restoreService.restore(ready, mapping);
    }

    /**
     * 在 done 事件时刷出剩余内容并清理缓冲。
     */
    public String flush(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return "";
        }
        StringBuilder buffer = bufferStore.remove(conversationId);
        if (buffer == null || buffer.isEmpty()) {
            return "";
        }
        Map<String, String> mapping = mappingRepository.get(conversationId);
        return restoreService.restore(buffer.toString(), mapping);
    }

    /**
     * 主动清理缓冲（异常/中断场景）。
     */
    public void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        bufferStore.remove(conversationId);
    }

    /**
     * 计算本轮可安全输出的位置。
     * 规则：如果末尾存在未闭合 '<...'
     * 则仅输出该未闭合段之前内容。
     */
    private int calculateSafeCut(StringBuilder buffer) {
        String text = buffer.toString();
        int lastOpen = text.lastIndexOf('<');
        int lastClose = text.lastIndexOf('>');
        if (lastOpen > lastClose) {
            return lastOpen;
        }
        return text.length();
    }
}
