package com.chtholly.agent.tools;

import com.chtholly.agent.web.RobotsPolicyService;
import com.chtholly.agent.web.SafeWebHttpClient;
import com.chtholly.agent.web.WebPageExtractor;
import com.chtholly.agent.web.WebResearchConfiguration;
import com.chtholly.agent.web.WebSearchProvider;
import com.chtholly.agent.web.WebUrlPolicy;
import com.chtholly.common.ratelimit.RateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebResearchConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    WebResearchConfiguration.class,
                    WebSearchTool.class,
                    WebFetchTool.class,
                    Dependencies.class);

    @Test
    void keepsWebResearchCompletelyDisabledUntilLlmIsEnabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(WebSearchTool.class);
            assertThat(context).doesNotHaveBean(WebFetchTool.class);
            assertThat(context).doesNotHaveBean(WebSearchProvider.class);
        });
    }

    @Test
    void wiresToolsAndSafeBoundaryWithoutAdditionalConfiguration() {
        contextRunner.withPropertyValues("llm.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(WebSearchTool.class);
            assertThat(context).hasSingleBean(WebFetchTool.class);
            assertThat(context).hasSingleBean(WebUrlPolicy.class);
            assertThat(context).hasSingleBean(SafeWebHttpClient.class);
            assertThat(context).hasSingleBean(RobotsPolicyService.class);
            assertThat(context).hasSingleBean(WebPageExtractor.class);
            assertThat(context).hasSingleBean(WebSearchProvider.class);
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Dependencies {

        @Bean
        RateLimiter rateLimiter() {
            return mock(RateLimiter.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
