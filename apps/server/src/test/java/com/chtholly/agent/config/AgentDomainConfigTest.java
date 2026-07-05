package com.chtholly.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDomainConfigTest {

    @Test
    void given_agentDomainYaml_when_bind_then_loadsNestedDialogueConfig() throws Exception {
        ClassPathResource resource = new ClassPathResource("agent-domain.yml");

        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("agent-domain", resource);
        AgentDomainConfig config = new Binder(ConfigurationPropertySources.from(sources))
                .bind("agent.domain", AgentDomainConfig.class)
                .get();

        assertThat(config.getSystemPrompt().getErrorFallback()).contains("稍后再试");
        assertThat(config.getErrors().getQuestionEmpty()).isEqualTo("问题不能为空");
        assertThat(config.getBangumi().getSearchKeywords()).contains("番剧", "动画");
        assertThat(config.getContext().getUserLabel()).isEqualTo("User:");
    }
}
