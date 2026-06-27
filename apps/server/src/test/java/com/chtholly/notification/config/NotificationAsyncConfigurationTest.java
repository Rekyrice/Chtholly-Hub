package com.chtholly.notification.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationAsyncConfigurationTest {

    @Test
    void notificationExecutorUsesCallerRunsPolicyAndQueueCapacity() {
        NotificationAsyncConfiguration config = new NotificationAsyncConfiguration();
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.notificationExecutor();

        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(5);
        assertThat(executor.getQueueCapacity()).isEqualTo(200);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("notif-");
        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    @Test
    void providesAsyncUncaughtExceptionHandler() {
        NotificationAsyncConfiguration config = new NotificationAsyncConfiguration();
        assertThat(config.getAsyncUncaughtExceptionHandler()).isNotNull();
    }
}
