package com.chtholly.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPropertiesTest {

    @Test
    void defaultsToTenDecisionSteps() {
        AgentProperties properties = new AgentProperties();

        assertThat(properties.getMaxSteps()).isEqualTo(10);
    }

    @Test
    void applicationYamlFallsBackToTenDecisionSteps() throws IOException {
        String applicationYaml = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(applicationYaml).contains("max-steps: ${AGENT_MAX_STEPS:10}");
    }
}
