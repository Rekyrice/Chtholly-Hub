package com.chtholly.counter.event;

import com.chtholly.common.kafka.KafkaRetryEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Consumes counter events in batches and acknowledges only after the MySQL transaction commits. */
@Service
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true")
public class CounterAggregationKafkaConsumer {

    private static final String CONSUMER_GROUP = "counter-agg";

    private final ObjectMapper objectMapper;
    private final CounterAggregationProcessor processor;

    public CounterAggregationKafkaConsumer(ObjectMapper objectMapper, CounterAggregationProcessor processor) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    /** Parses and applies one broker batch before explicitly acknowledging it. */
    @KafkaListener(
            id = "counter-aggregation-events",
            topics = CounterTopics.EVENTS,
            groupId = CONSUMER_GROUP,
            containerFactory = "counterBatchKafkaListenerContainerFactory")
    public void onMessage(List<ConsumerRecord<String, String>> records, Acknowledgment acknowledgment) {
        apply(records, acknowledgment, false);
    }

    /** Handles legacy retry-topic records with the same durable inbox semantics. */
    @KafkaListener(
            id = "counter-aggregation-retry",
            topics = CounterTopics.EVENTS + "-retry",
            groupId = CONSUMER_GROUP + "-retry",
            containerFactory = "counterBatchKafkaListenerContainerFactory")
    public void onRetryMessage(List<ConsumerRecord<String, String>> records, Acknowledgment acknowledgment) {
        apply(records, acknowledgment, true);
    }

    private void apply(
            List<ConsumerRecord<String, String>> records,
            Acknowledgment acknowledgment,
            boolean legacyRetryEnvelope) {
        Objects.requireNonNull(acknowledgment, "acknowledgment");
        if (records == null || records.isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }
        List<CounterEvent> events = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            try {
                String payload = record.value();
                if (legacyRetryEnvelope) {
                    KafkaRetryEnvelope envelope = objectMapper.readValue(payload, KafkaRetryEnvelope.class);
                    if (!CounterTopics.EVENTS.equals(envelope.sourceTopic())
                            || envelope.retryCount() < 1
                            || envelope.messageValue() == null) {
                        throw new IllegalArgumentException("Counter retry envelope is invalid");
                    }
                    payload = envelope.messageValue();
                }
                events.add(objectMapper.readValue(payload, CounterEvent.class));
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("Counter event payload is invalid", exception);
            }
        }
        processor.applyBatch(List.copyOf(events));
        acknowledgment.acknowledge();
    }
}
