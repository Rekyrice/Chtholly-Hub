package com.chtholly.post.outbox;

import com.chtholly.common.kafka.AbstractKafkaConsumer;
import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.chtholly.common.util.OutboxMessageUtil;
import com.chtholly.relation.outbox.OutboxTopics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/** Consumes durable post Outbox rows and repairs idempotent non-MySQL projections. */
@Service
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true")
public class PostOutboxProjectionConsumer extends AbstractKafkaConsumer {

    private static final String CONSUMER_GROUP = "post-projection-consumer";
    private final PostOutboxProjectionProcessor projectionProcessor;

    /** Creates the post projection Outbox consumer. */
    public PostOutboxProjectionConsumer(
            ObjectMapper objectMapper,
            PostOutboxProjectionProcessor projectionProcessor,
            KafkaTemplate<String, String> kafka,
            DeadLetterMessageService deadLetterMessageService) {
        super(kafka, objectMapper, deadLetterMessageService);
        this.projectionProcessor = projectionProcessor;
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        consumeRecord(record, acknowledgment);
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX_RETRY, groupId = CONSUMER_GROUP + "-retry")
    public void onRetryMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        consumeRetryRecord(record, acknowledgment);
    }

    @Override
    protected void process(String sourceTopic, String messageKey, String payload, int retryCount)
            throws Exception {
        for (JsonNode row : OutboxMessageUtil.extractRows(objectMapper, payload)) {
            processRow(row);
        }
    }

    private void processRow(JsonNode row) throws Exception {
        if (!"post".equals(row.path("aggregate_type").asText())) {
            return;
        }
        Long eventId = OutboxMessageUtil.extractEventId(row);
        if (eventId == null) {
            throw new IllegalArgumentException("Post Outbox event ID is required");
        }
        String eventType = requiredText(row, "type");
        long aggregatePostId = requirePositiveLong(row, "aggregate_id");
        long payloadPostId = requirePayloadPostId(row);
        if (aggregatePostId != payloadPostId) {
            throw new IllegalArgumentException(
                    "Post Outbox aggregate_id %d does not match payload id %d"
                            .formatted(aggregatePostId, payloadPostId));
        }
        projectionProcessor.process(eventId, eventType, aggregatePostId);
    }

    private long requirePayloadPostId(JsonNode row) throws Exception {
        JsonNode payloadNode = row.get("payload");
        if (payloadNode == null || !payloadNode.isTextual()) {
            throw new IllegalArgumentException("Post Outbox payload is required");
        }
        JsonNode eventPayload = objectMapper.readTree(payloadNode.asText());
        JsonNode idNode = eventPayload.get("id");
        if (idNode == null || !idNode.canConvertToLong()) {
            throw new IllegalArgumentException("Post Outbox aggregate ID is required");
        }
        long postId = idNode.asLong();
        if (postId <= 0L) {
            throw new IllegalArgumentException("Post Outbox aggregate ID must be positive");
        }
        return postId;
    }

    private static long requirePositiveLong(JsonNode row, String field) {
        JsonNode value = row.get(field);
        long parsed;
        if (value != null && value.isIntegralNumber() && value.canConvertToLong()) {
            parsed = value.asLong();
        } else if (value != null && value.isTextual()) {
            try {
                parsed = Long.parseLong(value.asText());
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(
                        "Post Outbox " + field + " must be a positive integer", invalid);
            }
        } else {
            throw new IllegalArgumentException("Post Outbox " + field + " is required");
        }
        if (parsed <= 0L) {
            throw new IllegalArgumentException(
                    "Post Outbox " + field + " must be a positive integer");
        }
        return parsed;
    }

    private static String requiredText(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Post Outbox " + field + " is required");
        }
        return value.asText();
    }

    @Override
    protected String consumerName() {
        return CONSUMER_GROUP;
    }
}
