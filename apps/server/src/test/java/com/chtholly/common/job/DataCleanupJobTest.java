package com.chtholly.common.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataCleanupJobTest {

    @Mock
    private BatchDeleteService batchDeleteService;

    private DataCleanupJob job;

    @BeforeEach
    void setUp() {
        CleanupProperties properties = new CleanupProperties(
                new CleanupProperties.Retention(90),
                new CleanupProperties.Retention(7),
                new CleanupProperties.Retention(90),
                new CleanupProperties.Retention(30),
                new CleanupProperties.Retention(30),
                new CleanupProperties.FeedPages(1000));
        job = new DataCleanupJob(batchDeleteService, properties);
    }

    @Test
    void cleanLoginLogsUsesConfiguredRetentionDays() {
        when(batchDeleteService.deleteInBatches(any(), eq(90))).thenReturn(3);

        assertThat(job.cleanLoginLogs()).isEqualTo(3);

        verify(batchDeleteService).deleteInBatches(
                eq("DELETE FROM login_logs WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY) LIMIT 1000"),
                eq(90));
    }

    @Test
    void cleanNotificationsOnlyTargetsReadRows() {
        when(batchDeleteService.deleteInBatches(any(), eq(90))).thenReturn(5);

        assertThat(job.cleanNotifications()).isEqualTo(5);

        verify(batchDeleteService).deleteInBatches(
                eq("DELETE FROM notifications WHERE read_at IS NOT NULL "
                        + "AND created_at < DATE_SUB(NOW(), INTERVAL ? DAY) LIMIT 1000"),
                eq(90));
    }

    @Test
    void cleanDeadLettersOnlyTargetsDeadStatus() {
        when(batchDeleteService.deleteInBatches(any(), eq(30))).thenReturn(2);

        assertThat(job.cleanDeadLetters()).isEqualTo(2);

        verify(batchDeleteService).deleteInBatches(
                eq("DELETE FROM dead_letter_messages WHERE status = 'DEAD' "
                        + "AND created_at < DATE_SUB(NOW(), INTERVAL ? DAY) LIMIT 1000"),
                eq(30));
    }
}
