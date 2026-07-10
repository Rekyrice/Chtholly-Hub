package com.chtholly.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Immutable root configuration for agent prompts and tool messages.
 */
@ConfigurationProperties(prefix = "agent.domain")
public record AgentDomainConfig(
        AgentSystemPromptConfig systemPrompt,
        AgentErrorMessages errors,
        BangumiDomainConfig bangumi,
        AgentContextLabels context
) {
    /** Renders named placeholders such as {@code {toolName}}. */
    public String render(String template, Object... keyValues) {
        String result = template == null ? "" : template;
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            result = result.replace("{" + keyValues[i] + "}", String.valueOf(keyValues[i + 1]));
        }
        return result;
    }
}
