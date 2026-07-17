package com.chtholly.agent.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TopicClusterWarmupTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(TopicClusteringService.class, () -> mock(TopicClusteringService.class))
            .withUserConfiguration(WarmupConfiguration.class);

    @Mock
    private TopicClusteringService topicClusteringService;

    @Test
    void refreshIfMissing_delegatesToTopicClusteringService() {
        TopicClusterWarmup warmup = new TopicClusterWarmup(topicClusteringService);

        warmup.refreshIfMissing();

        verify(topicClusteringService).refreshIfMissing();
    }

    @Test
    void refreshIfMissing_doesNotPropagateRefreshFailure() {
        doThrow(new IllegalStateException("redis unavailable"))
                .when(topicClusteringService).refreshIfMissing();
        TopicClusterWarmup warmup = new TopicClusterWarmup(topicClusteringService);

        assertThatCode(warmup::refreshIfMissing).doesNotThrowAnyException();
        verify(topicClusteringService).refreshIfMissing();
    }

    @Test
    void defaultContextRegistersWarmup() {
        contextRunner.run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(TopicClusterWarmup.class));
    }

    @Test
    void readOnlyCliContextDoesNotRegisterWarmup() {
        contextRunner
                .withPropertyValues("seed.cli-read-only=true")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(TopicClusterWarmup.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TopicClusterWarmup.class)
    static class WarmupConfiguration {
    }
}
