package com.chtholly.agent.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates the public-web research boundary without adding application configuration. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class WebResearchConfiguration {

    @Bean
    @ConditionalOnMissingBean
    WebUrlPolicy webUrlPolicy() {
        return new WebUrlPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    SafeWebHttpClient safeWebHttpClient(WebUrlPolicy webUrlPolicy) {
        return new SafeWebHttpClient(webUrlPolicy);
    }

    @Bean
    @ConditionalOnMissingBean
    RobotsPolicyService robotsPolicyService(SafeWebHttpClient safeWebHttpClient) {
        return new RobotsPolicyService(safeWebHttpClient);
    }

    @Bean
    @ConditionalOnMissingBean
    WebPageExtractor webPageExtractor() {
        return new WebPageExtractor();
    }

    @Bean
    @ConditionalOnMissingBean(WebSearchProvider.class)
    WebSearchProvider webSearchProvider(SafeWebHttpClient safeWebHttpClient) {
        return new DuckDuckGoHtmlSearchProvider(safeWebHttpClient);
    }
}
