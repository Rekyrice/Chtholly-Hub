package com.chtholly.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration switches for optional agent extensions.
 */
@Data
@ConfigurationProperties(prefix = "agent.extensions")
public class AgentExtensionProperties {

    private Toggle content = new Toggle();
    private Toggle graph = new Toggle();
    private Toggle learning = new Toggle();
    private Toggle experience = new Toggle();
    private Toggle mood = new Toggle();
    private Toggle proactive = new Toggle();
    private Toggle communityActions = new Toggle();

    /**
     * Reports whether every optional extension is disabled.
     *
     * @return {@code true} only when all extension switches are disabled
     */
    public boolean allDisabled() {
        return !content.isEnabled()
                && !graph.isEnabled()
                && !learning.isEnabled()
                && !experience.isEnabled()
                && !mood.isEnabled()
                && !proactive.isEnabled()
                && !communityActions.isEnabled();
    }

    /**
     * Mutable switch value used by Spring Boot property binding.
     */
    @Data
    public static class Toggle {

        private boolean enabled = true;
    }
}
