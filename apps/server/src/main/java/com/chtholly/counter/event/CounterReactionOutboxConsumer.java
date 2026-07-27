package com.chtholly.counter.event;

import com.chtholly.common.kafka.AbstractKafkaConsumer;
import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.chtholly.common.util.OutboxMessageUtil;
import com.chtholly.counter.mapper.CounterReactionKey;
import com.chtholly.counter.service.CounterReactionCommandService;
import com.chtholly.counter.service.impl.CounterReactionProjectionStore.ProjectionBatchException;
import com.chtholly.relation.outbox.OutboxTopics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Consumes reaction rows from the existing Canal Outbox topic. */
@Service
@ConditionalOnExpression("${kafka.enabled:false} && ${canal.enabled:false}")
public class CounterReactionOutboxConsumer extends AbstractKafkaConsumer {

    private static final String CONSUMER_GROUP = "counter-reaction-outbox";

    private final CounterReactionEventProcessor processor;

    public CounterReactionOutboxConsumer(
            ObjectMapper objectMapper,
            CounterReactionEventProcessor processor,
            KafkaTemplate<String, String> kafka,
            DeadLetterMessageService deadLetterMessageService) {
        super(kafka, objectMapper, deadLetterMessageService);
        this.processor = processor;
    }

    /** Consumes one Canal envelope and acknowledges it after durable core processing. */
    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        consumeRecord(record, acknowledgment);
    }

    /** Consumes a durable retry envelope through the same processing core. */
    @KafkaListener(
            topics = OutboxTopics.CANAL_OUTBOX_RETRY,
            groupId = CONSUMER_GROUP + "-retry")
    public void onRetryMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        consumeRetryRecord(record, acknowledgment);
    }

    @Override
    protected void process(
            String sourceTopic,
            String messageKey,
            String payload,
            int retryCount) throws Exception {
        List<CounterEvent> events = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();
        for (JsonNode row : OutboxMessageUtil.extractRows(objectMapper, payload)) {
            try {
                validateOutboxRow(row);
                if (!CounterReactionCommandService.OUTBOX_AGGREGATE_TYPE.equals(
                        row.get("aggregate_type").asText())
                        || !CounterReactionCommandService.OUTBOX_EVENT_TYPE.equals(
                        row.get("type").asText())) {
                    continue;
                }
                events.add(mapReactionRow(row));
            } catch (RuntimeException exception) {
                failures.add(exception);
            } catch (Exception exception) {
                failures.add(new IllegalArgumentException(
                        "Counter reaction Outbox payload is invalid", exception));
            }
        }
        if (!events.isEmpty()) {
            processIsolatingFailures(List.copyOf(events), failures);
        }
        throwIfFailed(failures);
    }

    /**
     * Preserves one shared transaction on the healthy path. A completed Redis pipeline may report
     * exact failed relation keys; those events stay pending while all other peers retry once as a
     * single batch. Unattributed and infrastructure failures are never multiplied.
     */
    private void processIsolatingFailures(
            List<CounterEvent> events,
            List<RuntimeException> failures) {
        try {
            processor.process(events);
        } catch (ProjectionBatchException exception) {
            List<CounterEvent> healthy = withoutFailedKeys(events, exception.failedKeys());
            if (healthy.size() == events.size()) {
                throw exception;
            }
            if (!healthy.isEmpty()) {
                try {
                    processor.process(healthy);
                } catch (RuntimeException healthyFailure) {
                    if (healthyFailure != exception) {
                        healthyFailure.addSuppressed(exception);
                    }
                    throw healthyFailure;
                }
            }
            failures.add(exception);
        }
    }

    private static List<CounterEvent> withoutFailedKeys(
            List<CounterEvent> events,
            java.util.Set<CounterReactionKey> failedKeys) {
        return events.stream()
                .filter(event -> !failedKeys.contains(
                        CounterReactionEventProcessor.validateAndMap(event)))
                .toList();
    }

    private static void validateOutboxRow(JsonNode row) {
        if (row == null || !row.isObject()) {
            throw new IllegalArgumentException("Outbox row must be a JSON object");
        }
        Long outboxId = OutboxMessageUtil.extractEventId(row);
        if (outboxId == null || outboxId <= 0L) {
            throw new IllegalArgumentException("Outbox row event ID is required");
        }
        requireTextualField(row, "aggregate_type");
        requireTextualField(row, "type");
        requireTextualField(row, "payload");
    }

    private static void requireTextualField(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Outbox row " + field + " is required");
        }
    }

    private CounterEvent mapReactionRow(JsonNode row) throws Exception {
        Long outboxId = OutboxMessageUtil.extractEventId(row);
        if (outboxId == null || outboxId <= 0L) {
            throw new IllegalArgumentException("Counter reaction Outbox event ID is required");
        }
        JsonNode payloadNode = row.get("payload");
        if (payloadNode == null || !payloadNode.isTextual() || payloadNode.asText().isBlank()) {
            throw new IllegalArgumentException("Counter reaction Outbox payload is required");
        }
        CounterEvent event = objectMapper.readValue(payloadNode.asText(), CounterEvent.class);
        if (event == null) {
            throw new IllegalArgumentException(
                    "Counter reaction Outbox payload must contain an event");
        }
        if (!Long.toString(outboxId).equals(event.getEventId())) {
            throw new IllegalArgumentException(
                    "Counter reaction Outbox event ID does not match payload event ID");
        }
        CounterReactionEventProcessor.validateAndMap(event);
        return event;
    }

    private static void throwIfFailed(List<RuntimeException> failures) {
        if (failures.isEmpty()) {
            return;
        }
        RuntimeException primary = failures.getFirst();
        for (int index = 1; index < failures.size(); index++) {
            RuntimeException failure = failures.get(index);
            if (failure != primary) {
                primary.addSuppressed(failure);
            }
        }
        throw primary;
    }

    @Override
    protected String consumerName() {
        return CONSUMER_GROUP;
    }
}
