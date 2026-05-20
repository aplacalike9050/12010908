package com.sustech.privacyaiproject.service;
//对 LLM 响应做逆向替换（占位符/合成值还原）
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Map;

/**
 * 响应还原服务。
 * <p>
 * 将 LLM 返回中的占位符/合成值还原为用户真实数据。
 */
@Service
public class RestoreService {

    /**
     * 根据映射表执行逆替换。
     *
     * @param aiText LLM 输出文本
     * @param mapping 安全值 -> 原始值
     * @return 还原后文本
     */
    public String restore(String aiText, Map<String, String> mapping) {
        if (aiText == null || aiText.isBlank() || mapping == null || mapping.isEmpty()) {
            return aiText == null ? "" : aiText;
        }
        String restored = aiText;
        // 先替换长度更长的 key，减少短 key 覆盖长 key 的风险
        for (Map.Entry<String, String> entry : mapping.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed())
                .toList()) {
            String safeValue = entry.getKey();
            String rawValue = entry.getValue();
            if (safeValue == null || safeValue.isBlank() || rawValue == null) {
                continue;
            }
            restored = restored.replace(safeValue, rawValue);
        }
        return restored;
    }
}
