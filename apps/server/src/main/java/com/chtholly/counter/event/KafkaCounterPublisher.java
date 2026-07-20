package com.chtholly.counter.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kafka 模式：计数事件写入 Kafka，并同步发布 Spring 事件供通知等本地监听器消费。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true")
public class KafkaCounterPublisher implements CounterEventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final int maxAttempts;
    private final Duration acknowledgmentTimeout;

    public KafkaCounterPublisher(
            KafkaTemplate<String, String> kafka,
            ObjectMapper objectMapper,
            ApplicationEventPublisher applicationEventPublisher,
            @Value("${counter.kafka.publish.max-attempts:3}") int maxAttempts,
            @Value("${counter.kafka.publish.ack-timeout:PT3S}") Duration acknowledgmentTimeout) {
        this.kafka = Objects.requireNonNull(kafka, "kafka");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.applicationEventPublisher = Objects.requireNonNull(applicationEventPublisher, "applicationEventPublisher");
        if (maxAttempts < 1) { throw new IllegalArgumentException("maxAttempts must be positive"); }
        if (acknowledgmentTimeout == null || acknowledgmentTimeout.isZero() || acknowledgmentTimeout.isNegative()) {
            throw new IllegalArgumentException("acknowledgmentTimeout must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.acknowledgmentTimeout = acknowledgmentTimeout;
    }

    @Override
    public void publish(CounterEvent event) {
        Objects.requireNonNull(event, "event");
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize counter event", e);
        }
        String key = partitionKey(event);
        Throwable lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                kafka.send(CounterTopics.EVENTS, key, payload)
                        .get(acknowledgmentTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while publishing counter event", exception);
            } catch (ExecutionException | TimeoutException | RuntimeException exception) {
                lastFailure = exception;
                log.warn("Counter event publish attempt {}/{} failed eventId={} cause={}",
                        attempt, maxAttempts, event.getEventId(), exception.toString());
                continue;
            }
            try {
                applicationEventPublisher.publishEvent(event);
            } catch (RuntimeException exception) {
                log.error("Counter event local publication failed after broker acknowledgment eventId={}",
                        event.getEventId(), exception);
            }
            return;
        }
        throw new IllegalStateException(
                "Counter event delivery failed after " + maxAttempts + " attempts", lastFailure);
    }

    private static String partitionKey(CounterEvent event) {
        if (event.getEventId() == null || event.getEventId().isBlank()
                || event.getEntityType() == null || event.getEntityType().isBlank()
                || event.getEntityId() == null || event.getEntityId().isBlank()
                || event.getMetric() == null || event.getMetric().isBlank()) {
            throw new IllegalArgumentException("Counter event identity is required");
        }
        return event.getEntityType() + ":" + event.getEntityId() + ":" + event.getMetric();
    }
}
