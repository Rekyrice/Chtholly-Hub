package com.chtholly.counter.event;

import com.chtholly.common.kafka.KafkaRetryEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CounterAggregationKafkaConsumerTest {

    @Mock
    private CounterAggregationProcessor processor;
    @Mock
    private Acknowledgment acknowledgment;

    @Test
    void successfulBatchAcknowledgesAfterTransactionalProcessorReturns() throws Exception {
        CounterEvent event = CounterEvent.of("evt-1", "post", "7", "like", 1, 100L, 1);
        ObjectMapper objectMapper = new ObjectMapper();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                CounterTopics.EVENTS, 0, 1L, "post:7:like", objectMapper.writeValueAsString(event));
        when(processor.applyBatch(List.of(event))).thenReturn(1);
        CounterAggregationKafkaConsumer consumer = new CounterAggregationKafkaConsumer(objectMapper, processor);

        consumer.onMessage(List.of(record), acknowledgment);

        verify(processor).applyBatch(List.of(event));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void processorFailureDoesNotAcknowledge() throws Exception {
        CounterEvent event = CounterEvent.of("evt-1", "post", "7", "like", 1, 100L, 1);
        ObjectMapper objectMapper = new ObjectMapper();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                CounterTopics.EVENTS, 0, 1L, "post:7:like", objectMapper.writeValueAsString(event));
        when(processor.applyBatch(List.of(event))).thenThrow(new IllegalStateException("mysql down"));
        CounterAggregationKafkaConsumer consumer = new CounterAggregationKafkaConsumer(objectMapper, processor);

        assertThatThrownBy(() -> consumer.onMessage(List.of(record), acknowledgment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mysql down");

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void malformedRecordDoesNotPartiallyInvokeProcessorOrAcknowledge() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                CounterTopics.EVENTS, 0, 1L, "post:7:like", "not-json");
        CounterAggregationKafkaConsumer consumer = new CounterAggregationKafkaConsumer(
                new ObjectMapper(), processor);

        assertThatThrownBy(() -> consumer.onMessage(List.of(record), acknowledgment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");

        verify(processor, never()).applyBatch(org.mockito.ArgumentMatchers.anyList());
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void legacyRetryEnvelopeIsUnwrappedBeforeApplyingTheBatch() throws Exception {
        CounterEvent event = CounterEvent.of("evt-1", "post", "7", "like", 1, 100L, 1);
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaRetryEnvelope envelope = new KafkaRetryEnvelope(
                CounterTopics.EVENTS,
                "post:7:like",
                objectMapper.writeValueAsString(event),
                2,
                0L);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                CounterTopics.EVENTS + "-retry",
                0,
                1L,
                "post:7:like",
                objectMapper.writeValueAsString(envelope));
        when(processor.applyBatch(List.of(event))).thenReturn(1);
        CounterAggregationKafkaConsumer consumer = new CounterAggregationKafkaConsumer(objectMapper, processor);

        consumer.onRetryMessage(List.of(record), acknowledgment);

        verify(processor).applyBatch(List.of(event));
        verify(acknowledgment).acknowledge();
    }
}
