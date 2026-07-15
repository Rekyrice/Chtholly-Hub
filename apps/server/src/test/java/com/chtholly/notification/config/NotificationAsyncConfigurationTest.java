package com.chtholly.notification.config;

import com.chtholly.common.tracing.CorrelationIdSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationAsyncConfigurationTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void notificationExecutorUsesCallerRunsPolicyAndQueueCapacity() throws Exception {
        NotificationAsyncConfiguration config = new NotificationAsyncConfiguration();
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.notificationExecutor();

        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(5);
            assertThat(executor.getQueueCapacity()).isEqualTo(200);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("notif-");
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);

            CorrelationIdSupport.putHttp("notif-async", "POST", "/internal");
            AtomicReference<String> asyncId = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            executor.execute(() -> {
                asyncId.set(MDC.get(CorrelationIdSupport.MDC_CORRELATION_ID));
                completed.countDown();
            });
            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(asyncId.get()).isEqualTo("notif-async");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void providesAsyncUncaughtExceptionHandler() {
        NotificationAsyncConfiguration config = new NotificationAsyncConfiguration();
        assertThat(config.getAsyncUncaughtExceptionHandler()).isNotNull();
    }
}
