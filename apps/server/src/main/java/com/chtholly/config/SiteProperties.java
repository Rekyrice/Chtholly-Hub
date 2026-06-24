package com.chtholly.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 站点级配置，绑定前缀 {@code site.*}。
 */
@ConfigurationProperties(prefix = "site")
public record SiteProperties(long ownerUserId) {}
