package com.chtholly.common.job;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据生命周期清理配置。
 */
@ConfigurationProperties(prefix = "cleanup")
public record CleanupProperties(
        Retention loginLogs,
        Retention outbox,
        Retention notifications,
        Retention bangumiSyncLog,
        Retention deadLetter,
        FeedPages feedPages
) {
    public record Retention(int retentionDays) {
    }

    public record FeedPages(int maxSize) {
    }
}
