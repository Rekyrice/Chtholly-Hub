package com.chtholly.counter.event;

import com.chtholly.common.kafka.AbstractKafkaConsumer;
import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.chtholly.common.util.OutboxMessageUtil;
import com.chtholly.counter.service.CounterReactionCommandService;
import com.chtholly.relation.outbox.OutboxTopics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Consumes reaction rows from the existing Canal Outbox topic. */
@Service
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true")
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
            topics = OutboxTopics.CANAL_OUTBOX + "-retry",
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
        for (JsonNode row : OutboxMessageUtil.extractRows(objectMapper, payload)) {
            if (!CounterReactionCommandService.OUTBOX_AGGREGATE_TYPE.equals(
                    row.path("aggregate_type").asText())
                    || !CounterReactionCommandService.OUTBOX_EVENT_TYPE.equals(
                    row.path("type").asText())) {
                continue;
            }
            Long outboxId = OutboxMessageUtil.extractEventId(row);
            if (outboxId == null || outboxId <= 0L) {
                throw new IllegalArgumentException("Counter reaction Outbox event ID is required");
            }
            JsonNode payloadNode = row.get("payload");
            if (payloadNode == null || !payloadNode.isTextual() || payloadNode.asText().isBlank()) {
                throw new IllegalArgumentException("Counter reaction Outbox payload is required");
            }
            CounterEvent event = objectMapper.readValue(payloadNode.asText(), CounterEvent.class);
            if (!Long.toString(outboxId).equals(event.getEventId())) {
                throw new IllegalArgumentException(
                        "Counter reaction Outbox event ID does not match payload event ID");
            }
            events.add(event);
        }
        if (!events.isEmpty()) {
            processor.process(List.copyOf(events));
        }
    }

    @Override
    protected String consumerName() {
        return CONSUMER_GROUP;
    }
}
