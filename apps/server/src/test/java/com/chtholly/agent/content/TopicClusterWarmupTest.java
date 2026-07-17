package com.chtholly.agent.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TopicClusterWarmupTest {

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
}
