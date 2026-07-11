package com.chtholly.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExtensionPropertiesTest {

    @Test
    void enablesAllExtensionsByDefault() {
        AgentExtensionProperties properties = new AgentExtensionProperties();

        assertThat(properties.getContent().isEnabled()).isTrue();
        assertThat(properties.getGraph().isEnabled()).isTrue();
        assertThat(properties.getLearning().isEnabled()).isTrue();
        assertThat(properties.getExperience().isEnabled()).isTrue();
        assertThat(properties.getMood().isEnabled()).isTrue();
        assertThat(properties.getProactive().isEnabled()).isTrue();
        assertThat(properties.getCommunityActions().isEnabled()).isTrue();
    }

    @Test
    void reportsAllDisabledWhenEveryExtensionIsBoundToFalse() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.extensions.content.enabled", "false")
                .withProperty("agent.extensions.graph.enabled", "false")
                .withProperty("agent.extensions.learning.enabled", "false")
                .withProperty("agent.extensions.experience.enabled", "false")
                .withProperty("agent.extensions.mood.enabled", "false")
                .withProperty("agent.extensions.proactive.enabled", "false")
                .withProperty("agent.extensions.community-actions.enabled", "false");

        AgentExtensionProperties properties = Binder.get(environment)
                .bind("agent.extensions", AgentExtensionProperties.class)
                .orElseThrow(IllegalStateException::new);

        assertThat(properties.allDisabled()).isTrue();
    }
}
