package com.chtholly.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    @Test
    void environmentExamplesDefaultToTenDecisionSteps() throws IOException {
        Path repositoryRoot = findRepositoryRoot();

        assertThat(Files.readAllLines(repositoryRoot.resolve(".env.example"), StandardCharsets.UTF_8))
                .contains("AGENT_MAX_STEPS=10");
        assertThat(Files.readAllLines(repositoryRoot.resolve(".env.prod.example"), StandardCharsets.UTF_8))
                .contains("AGENT_MAX_STEPS=10");
    }

    private Path findRepositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("apps/server/pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
