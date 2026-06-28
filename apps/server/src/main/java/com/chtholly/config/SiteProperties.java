package com.chtholly.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 站点级配置，绑定前缀 {@code site.*}。
 */
@ConfigurationProperties(prefix = "site")
public record SiteProperties(
        long ownerUserId,
        String ownerBootstrapPassword,
        String ownerHandle,
        String ownerNickname
) {
    public SiteProperties {
        if (ownerBootstrapPassword == null) {
            ownerBootstrapPassword = "";
        }
        if (ownerHandle == null || ownerHandle.isBlank()) {
            ownerHandle = "Rekyrice";
        }
        if (ownerNickname == null || ownerNickname.isBlank()) {
            ownerNickname = "Rekyrice";
        }
    }
}
