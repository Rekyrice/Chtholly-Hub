package com.chtholly.relation.outbox;

import com.chtholly.common.kafka.AbstractKafkaConsumer;
import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.chtholly.common.kafka.idempotency.OutboxIdempotencyGuard;
import com.chtholly.common.util.OutboxMessageUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chtholly.relation.event.RelationEvent;
import com.chtholly.relation.processor.RelationEventProcessor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Canal Outbox 消费者。
 * 职责：消费 Canal 桥接写入的 outbox 主题消息，提取 payload 并反序列化为 RelationEvent，交由处理器落库与更新缓存/计数。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true")
public class CanalOutboxConsumer extends AbstractKafkaConsumer {

    private static final String CONSUMER_GROUP = "relation-outbox-consumer";
    private static final String IDEMPOTENCY_SCOPE = "relation";
    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of("FollowCreated", "FollowCanceled");

    private final RelationEventProcessor processor;
    private final OutboxIdempotencyGuard idempotencyGuard;

    public CanalOutboxConsumer(ObjectMapper objectMapper,
                               RelationEventProcessor processor,
                               KafkaTemplate<String, String> kafka,
                               DeadLetterMessageService deadLetterMessageService,
                               OutboxIdempotencyGuard idempotencyGuard) {
        super(kafka, objectMapper, deadLetterMessageService);
        this.processor = processor;
        this.idempotencyGuard = idempotencyGuard;
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consumeRecord(record, ack);
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX + "-retry", groupId = CONSUMER_GROUP + "-retry")
    public void onRetryMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consumeRetryRecord(record, ack);
    }

    @Override
    protected void process(String sourceTopic, String messageKey, String payload, int retryCount) throws Exception {
        List<JsonNode> rows = OutboxMessageUtil.extractRows(objectMapper, payload);
        for (JsonNode row : rows) {
            if (!"following".equals(row.path("aggregate_type").asText())) {
                continue;
            }
            String eventType = row.path("type").asText();
            if (!SUPPORTED_EVENT_TYPES.contains(eventType)) {
                continue;
            }

            Long eventId = OutboxMessageUtil.extractEventId(row);
            if (eventId == null) {
                throw new IllegalArgumentException("Relation Outbox event ID is required");
            }
            JsonNode payloadNode = row.get("payload");
            if (payloadNode == null || !payloadNode.isTextual() || payloadNode.asText().isBlank()) {
                throw new IllegalArgumentException("Relation Outbox payload is required");
            }
            if (idempotencyGuard.isAlreadyConsumed(IDEMPOTENCY_SCOPE, eventId)) {
                continue;
            }

            RelationEvent evt = objectMapper.readValue(payloadNode.asText(), RelationEvent.class);
            if (!eventType.equals(evt.type())) {
                throw new IllegalArgumentException("Relation Outbox type does not match payload type");
            }
            try {
                processor.process(evt);
            } catch (Exception e) {
                log.error("Canal outbox processing failed", e);
                throw e;
            }
            idempotencyGuard.markConsumed(IDEMPOTENCY_SCOPE, eventId);
        }
    }

    @Override
    protected String consumerName() {
        return CONSUMER_GROUP;
    }
}
