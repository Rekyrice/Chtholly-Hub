package com.chtholly.common.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * MySQL 历史数据定时清理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataCleanupJob {

    private final BatchDeleteService batchDeleteService;
    private final CleanupProperties cleanupProperties;

    /** 每天凌晨 3 点：login_logs / outbox / notifications / dead_letter_messages。 */
    @Scheduled(cron = "0 0 3 * * *")
    public void runDailyCleanup() {
        int loginLogs = cleanLoginLogs();
        int outbox = cleanOutbox();
        int notifications = cleanNotifications();
        int deadLetters = cleanDeadLetters();

        log.info("[Cleanup] login_logs: deleted {} rows, outbox: deleted {} rows, "
                        + "notifications: deleted {} rows, dead_letter_messages: deleted {} rows",
                loginLogs, outbox, notifications, deadLetters);
    }

    /** 每周日凌晨 3 点：bangumi_sync_log。 */
    @Scheduled(cron = "0 0 3 ? * SUN")
    public void runWeeklyCleanup() {
        int bangumiSyncLog = cleanBangumiSyncLog();
        log.info("[Cleanup] bangumi_sync_log: deleted {} rows", bangumiSyncLog);
    }

    int cleanLoginLogs() {
        int days = cleanupProperties.loginLogs().retentionDays();
        String sql = "DELETE FROM login_logs WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY) LIMIT "
                + BatchDeleteService.BATCH_SIZE;
        return batchDeleteService.deleteInBatches(sql, days);
    }

    int cleanOutbox() {
        int days = cleanupProperties.outbox().retentionDays();
        String sql = "DELETE FROM outbox WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY) LIMIT "
                + BatchDeleteService.BATCH_SIZE;
        return batchDeleteService.deleteInBatches(sql, days);
    }

    int cleanNotifications() {
        int days = cleanupProperties.notifications().retentionDays();
        String sql = "DELETE FROM notifications WHERE read_at IS NOT NULL "
                + "AND created_at < DATE_SUB(NOW(), INTERVAL ? DAY) LIMIT "
                + BatchDeleteService.BATCH_SIZE;
        return batchDeleteService.deleteInBatches(sql, days);
    }

    int cleanDeadLetters() {
        int days = cleanupProperties.deadLetter().retentionDays();
        String sql = "DELETE FROM dead_letter_messages WHERE status = 'DEAD' "
                + "AND created_at < DATE_SUB(NOW(), INTERVAL ? DAY) LIMIT "
                + BatchDeleteService.BATCH_SIZE;
        return batchDeleteService.deleteInBatches(sql, days);
    }

    int cleanBangumiSyncLog() {
        int days = cleanupProperties.bangumiSyncLog().retentionDays();
        String sql = "DELETE FROM bangumi_sync_log WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY) LIMIT "
                + BatchDeleteService.BATCH_SIZE;
        return batchDeleteService.deleteInBatches(sql, days);
    }
}
