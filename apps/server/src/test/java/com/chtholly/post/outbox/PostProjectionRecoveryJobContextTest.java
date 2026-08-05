package com.chtholly.post.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PostProjectionRecoveryJobContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(PostProjectionReceiptMapper.class,
                    () -> mock(PostProjectionReceiptMapper.class))
            .withBean(PostOutboxProjectionProcessor.class,
                    () -> mock(PostOutboxProjectionProcessor.class))
            .withUserConfiguration(PostProjectionRecoveryJob.class);

    @Test
    void defaultLocalTransportEnablesDurableRecovery() {
        runner.withPropertyValues("kafka.enabled=false", "canal.enabled=false")
                .run(context -> assertThat(context)
                        .hasSingleBean(PostProjectionRecoveryJob.class));
    }

    @Test
    void completeKafkaAndCanalTransportKeepsDurableRecoveryEnabled() {
        runner.withPropertyValues("kafka.enabled=true", "canal.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(PostProjectionRecoveryJob.class));
    }
}
