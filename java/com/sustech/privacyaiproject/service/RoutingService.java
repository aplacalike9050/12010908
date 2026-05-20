package com.sustech.privacyaiproject.service;
//负责根据参数路由给具体的大模型
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoutingService {

    @Qualifier("deepseekModel")
    private final ChatModel deepseekModel;
    @Qualifier("geminiModel")
    private final ChatModel geminiModel;
    @Qualifier("openaiModel")
    private final ChatModel openaiModel;

    @Qualifier("deepseekStreamingModel")
    private final StreamingChatModel deepseekStreamingModel;
    @Qualifier("geminiStreamingModel")
    private final StreamingChatModel geminiStreamingModel;
    @Qualifier("openaiStreamingModel")
    private final StreamingChatModel openaiStreamingModel;

    /**
     * 根据模型提供方或模型别名解析非流式模型客户端。
     *
     * @param modelProvider OpenAI 请求中的 model 字段或历史 provider 名称
     * @return 已配置的模型客户端
     */
    public ChatModel resolveModel(String modelProvider) {
        String provider = normalize(modelProvider);
        return switch (provider) {
            case "deepseek", "deepseek-chat" -> deepseekModel;
            case "gemini", "gemini-3-flash-preview", "gemini-flash" -> geminiModel;
            case "chatgpt", "openai", "gpt-4.1-mini", "gpt-4o-mini" -> openaiModel;
            default -> geminiModel;
        };
    }

    /**
     * 根据模型提供方或模型别名解析流式模型客户端。
     *
     * @param modelProvider OpenAI 请求中的 model 字段或历史 provider 名称
     * @return 已配置的流式模型客户端
     */
    public StreamingChatModel resolveStreamingModel(String modelProvider) {
        String provider = normalize(modelProvider);
        return switch (provider) {
            case "deepseek", "deepseek-chat" -> deepseekStreamingModel;
            case "gemini", "gemini-3-flash-preview", "gemini-flash" -> geminiStreamingModel;
            case "chatgpt", "openai", "gpt-4.1-mini", "gpt-4o-mini" -> openaiStreamingModel;
            default -> geminiStreamingModel;
        };
    }

    /**
     * 规范化模型名称，兼容 provider 和 OpenAI model 两种输入。
     */
    private String normalize(String provider) {
        if (provider == null || provider.trim().isEmpty()) {
            return "gemini";
        }
        return provider.trim().toLowerCase();
    }
}
