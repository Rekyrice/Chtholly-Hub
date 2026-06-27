package com.chtholly.bangumi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bangumi API 配置。 */
@Data
@ConfigurationProperties(prefix = "bangumi")
public class BangumiProperties {
    private boolean enabled = true;
    private String baseUrl = "https://api.bgm.tv";
    private String accessToken = "";
    /** User-Agent 必填，否则可能被限流。 */
    private String userAgent = "ChthollyHub/1.0 (https://github.com/chtholly-hub)";
    /** 全局限流：每秒请求数。 */
    private int ratePerSecond = 1;
    private Sync sync = new Sync();

    @Data
    public static class Sync {
        private boolean enabled = false;
        private String cron = "0 0 4 * * *";
    }
}
