package com.chtholly.counter.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SpringEventCounterPublisherTest {

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private SpringEventCounterPublisher publisher;

    @Test
    void publishesCounterEventToApplicationEventBus() {
        CounterEvent event = CounterEvent.of("post", "1", "like", 0, 2L, 1);

        publisher.publish(event);

        ArgumentCaptor<CounterEvent> captor = ArgumentCaptor.forClass(CounterEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getEntityId()).isEqualTo("1");
    }
}
