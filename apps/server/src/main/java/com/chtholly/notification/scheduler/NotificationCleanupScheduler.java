package com.chtholly.notification.scheduler;

import com.chtholly.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时清理过期已读通知。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCleanupScheduler {

    private static final int RETENTION_DAYS = 90;

    private final NotificationService notificationService;

    /** 每天凌晨 3 点清理超过 90 天的已读通知。 */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanExpiredNotifications() {
        int removed = notificationService.cleanExpiredAll(RETENTION_DAYS);
        if (removed > 0) {
            log.info("已清理 {} 条过期已读通知（保留 {} 天）", removed, RETENTION_DAYS);
        }
    }
}
