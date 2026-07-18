package com.chtholly.counter.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaCounterPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafka;
    @Mock
    private ApplicationEventPublisher localEvents;

    @Test
    void retryReusesSameEventKeyAndSerializedPayload() {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        CompletableFuture<SendResult<String, String>> succeeded = CompletableFuture.completedFuture(null);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(failed, succeeded);
        CounterEvent event = CounterEvent.of("event-1", "post", "99", "like", 1, 42L, 1);
        KafkaCounterPublisher publisher = publisher(new ObjectMapper(), 2);

        publisher.publish(event);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(kafka, times(2)).send(anyString(), keys.capture(), payloads.capture());
        assertThat(keys.getAllValues()).containsOnly("post:99:like");
        assertThat(payloads.getAllValues()).hasSize(2).allMatch(payloads.getAllValues().getFirst()::equals);
        assertThat(payloads.getAllValues().getFirst()).contains("\"eventId\":\"event-1\"");
        verify(localEvents).publishEvent(event);
    }

    @Test
    void exhaustedAttemptsThrowWithoutPublishingLocalEvent() {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(failed);
        CounterEvent event = CounterEvent.of("event-2", "post", "99", "fav", 2, 42L, 1);

        assertThatThrownBy(() -> publisher(new ObjectMapper(), 3).publish(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3 attempts");

        verify(kafka, times(3)).send(anyString(), anyString(), anyString());
        verify(localEvents, never()).publishEvent(event);
    }

    @Test
    void serializationFailureThrowsBeforeAnyPublication() throws Exception {
        ObjectMapper objectMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        CounterEvent event = CounterEvent.of("event-3", "post", "99", "like", 1, 42L, 1);
        when(objectMapper.writeValueAsString(event)).thenThrow(new JsonProcessingException("bad payload") {});

        assertThatThrownBy(() -> publisher(objectMapper, 2).publish(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serialize");

        verifyNoInteractions(kafka, localEvents);
    }

    private KafkaCounterPublisher publisher(ObjectMapper objectMapper, int attempts) {
        return new KafkaCounterPublisher(kafka, objectMapper, localEvents, attempts, Duration.ofSeconds(1));
    }
}
