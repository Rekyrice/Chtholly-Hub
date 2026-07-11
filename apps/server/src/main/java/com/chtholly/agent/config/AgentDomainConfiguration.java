package com.chtholly.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers agent domain and extension configuration properties. */
@Configuration
@EnableConfigurationProperties({AgentDomainConfig.class, AgentExtensionProperties.class})
public class AgentDomainConfiguration {
}
