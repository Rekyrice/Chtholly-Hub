package com.chtholly.counter.event;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CounterAggregationSpringConsumerTest {

    private final CounterAggregationProcessor processor = mock(CounterAggregationProcessor.class);
    private final CounterAggregationSpringConsumer consumer =
            new CounterAggregationSpringConsumer(processor);

    @Test
    void keepsViewEventsOnTheLegacyLocalAggregationPath() {
        CounterEvent view = CounterEvent.of("view-1", "post", "7", "view", 0, 0L, 1);

        consumer.onCounterEvent(view);

        verify(processor).applyBatch(List.of(view));
    }

    @Test
    void reactionEventsAreNotAggregatedTwiceAfterSharedCorePublication() {
        CounterEvent like = CounterEvent.of("41", "post", "7", "like", 1, 42L, 1);

        consumer.onCounterEvent(like);

        verify(processor, never()).applyBatch(List.of(like));
    }
}
