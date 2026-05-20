package com.sustech.privacyaiproject.domain.dto.openai;

import lombok.Data;

/**
 * OpenAI Chat Completions 协议中的单条消息。
 * <p>
 * 当前阶段优先支持字符串 content，后续可扩展为多模态数组结构。
 */
@Data
public class OpenAiChatMessage {

    /**
     * 消息角色，例如 system、user、assistant、tool。
     */
    private String role;

    /**
     * 消息文本内容。
     */
    private String content;

    /**
     * tool 角色消息的工具调用 ID，当前仅透传保留。
     */
    private String toolCallId;
}
