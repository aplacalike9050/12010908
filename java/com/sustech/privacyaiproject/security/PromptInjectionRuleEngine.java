package com.sustech.privacyaiproject.security;

import com.sustech.privacyaiproject.domain.dto.security.PromptInjectionDetectionResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Prompt 注入启发式规则引擎。
 * <p>
 * 负责拦截高置信黑名单特征词，作为模型检测前的快速防线。
 */
@Component
public class PromptInjectionRuleEngine {

    private static final List<Rule> RULES = List.of(
            new Rule("IGNORE_PREVIOUS", "ignore previous instructions", 1.0D, "要求模型忽略此前指令"),
            new Rule("IGNORE_ABOVE_CN", "忽略以上指令", 1.0D, "要求模型忽略此前中文指令"),
            new Rule("IGNORE_PREVIOUS_CN", "忽略之前的指令", 1.0D, "要求模型忽略此前中文指令"),
            new Rule("IGNORE_FORMER_CN", "忽略前面的指令", 1.0D, "要求模型忽略此前中文指令"),
            new Rule("IGNORE_ALL_CN", "忽略所有指令", 1.0D, "要求模型忽略全部约束指令"),
            new Rule("DISREGARD_PREVIOUS_CN", "无视之前的指令", 1.0D, "要求模型无视此前中文指令"),
            new Rule("FORGET_PREVIOUS_CN", "忘记之前的指令", 1.0D, "要求模型忘记此前中文指令"),
            new Rule("SYSTEM_PROMPT_LEAK", "system prompt", 0.95D, "尝试读取或泄露系统提示词"),
            new Rule("SYSTEM_PROMPT_LEAK_CN", "系统提示词", 0.95D, "尝试读取或泄露系统提示词"),
            new Rule("SYSTEM_INSTRUCTION_LEAK_CN", "系统指令", 0.95D, "尝试读取或泄露系统指令"),
            new Rule("REVEAL_PROMPT_CN", "泄露提示词", 0.95D, "尝试泄露提示词"),
            new Rule("PRINT_PROMPT_CN", "输出提示词", 0.95D, "尝试输出提示词"),
            new Rule("SHOW_PROMPT_CN", "显示提示词", 0.95D, "尝试显示提示词"),
            new Rule("DEVELOPER_PROMPT_LEAK", "developer message", 0.95D, "尝试读取开发者消息"),
            new Rule("DEVELOPER_PROMPT_LEAK_CN", "开发者消息", 0.95D, "尝试读取开发者消息"),
            new Rule("JAILBREAK", "jailbreak", 0.95D, "疑似越狱提示"),
            new Rule("JAILBREAK_CN", "越狱", 0.95D, "疑似中文越狱提示"),
            new Rule("DAN", "do anything now", 0.95D, "DAN越狱提示"),
            new Rule("SECRET_LEAK", "输出 api key", 0.95D, "要求输出密钥"),
            new Rule("SECRET_LEAK_CN", "输出密钥", 0.95D, "要求输出密钥"),
            new Rule("PASSWORD_LEAK_CN", "泄露密钥", 0.95D, "要求泄露密钥"),
            new Rule("TOOL_OVERRIDE", "override tool", 0.9D, "尝试覆盖工具调用规则")
    );

    /**
     * 对输入文本执行启发式规则检测。
     *
     * @param text 用户输入文本
     * @return 检测结果
     */
    public PromptInjectionDetectionResult detect(String text) {
        if (text == null || text.isBlank()) {
            return PromptInjectionDetectionResult.safe();
        }
        String normalized = normalize(text);
        for (Rule rule : RULES) {
            if (normalized.contains(rule.keyword())) {
                return PromptInjectionDetectionResult.builder()
                        .malicious(true)
                        .score(rule.score())
                        .source("HEURISTIC_RULE")
                        .label(rule.code())
                        .reason(rule.reason())
                        .build();
            }
        }
        return PromptInjectionDetectionResult.safe();
    }

    /**
     * 归一化输入文本，提升中英文混合规则命中率。
     */
    private String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replace("：", ":")
                .replace("，", ",")
                .replace("。", ".")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record Rule(String code, String keyword, double score, String reason) {
    }
}
