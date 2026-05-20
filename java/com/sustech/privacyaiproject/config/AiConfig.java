package com.sustech.privacyaiproject.config;


import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Value("${ai.deepseek.api-key}")
    private String deepseekKey;
    @Value("${ai.deepseek.base-url}")
    private String deepseekUrl;
    @Value("${ai.deepseek.model-name}")
    private String deepseekModel;

    @Value("${ai.gemini.api-key}")
    private String geminiKey;
    @Value("${ai.gemini.base-url}")
    private String geminiUrl;
    @Value("${ai.gemini.model-name}")
    private String geminiModel;

    @Value("${ai.openai.api-key:}")
    private String openaiKey;
    @Value("${ai.openai.base-url:https://api.openai.com/v1}")
    private String openaiUrl;
    @Value("${ai.openai.model-name:gpt-4o-mini}")
    private String openaiModel;

    @Bean("geminiModel")
    public ChatModel geminiModel() {
        return OpenAiChatModel.builder()
                .baseUrl(geminiUrl)
                .apiKey(geminiKey)
                .modelName(geminiModel)
                .build();
    }

    @Bean("deepseekModel")
    public ChatModel deepseekModel() {
        return OpenAiChatModel.builder()
                .baseUrl(deepseekUrl)
                .apiKey(deepseekKey)
                .modelName(deepseekModel)
                .build();
    }

    @Bean("openaiModel")
    public ChatModel openaiModel() {
        return OpenAiChatModel.builder()
                .baseUrl(openaiUrl)
                .apiKey(openaiKey)
                .modelName(openaiModel)
                .build();
    }

    @Bean("geminiStreamingModel")
    public StreamingChatModel geminiStreamingModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(geminiUrl)
                .apiKey(geminiKey)
                .modelName(geminiModel)
                .build();
    }

    @Bean("deepseekStreamingModel")
    public StreamingChatModel deepseekStreamingModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(deepseekUrl)
                .apiKey(deepseekKey)
                .modelName(deepseekModel)
                .build();
    }

    @Bean("openaiStreamingModel")
    public StreamingChatModel openaiStreamingModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(openaiUrl)
                .apiKey(openaiKey)
                .modelName(openaiModel)
                .build();
    }

}