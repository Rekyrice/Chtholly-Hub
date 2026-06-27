package com.chtholly.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/** CORS 白名单配置，绑定 {@code app.cors.*}。 */
@Data
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /** 逗号分隔的允许来源，如 http://localhost:3000,https://example.com */
    private String allowedOrigins = "http://localhost:3000";

    public List<String> allowedOriginList() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return List.of("http://localhost:3000");
        }
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
