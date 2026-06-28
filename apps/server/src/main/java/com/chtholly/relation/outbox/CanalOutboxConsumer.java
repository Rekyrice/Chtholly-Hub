package com.chtholly.relation.outbox;

import com.chtholly.common.kafka.AbstractKafkaConsumer;
import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.chtholly.common.kafka.idempotency.OutboxIdempotencyGuard;
import com.chtholly.common.util.OutboxMessageUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chtholly.relation.event.RelationEvent;
import com.chtholly.relation.processor.RelationEventProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Canal Outbox 消费者。
 * 职责：消费 Canal 桥接写入的 outbox 主题消息，提取 payload 并反序列化为 RelationEvent，交由处理器落库与更新缓存/计数。
 */
@Service
public class CanalOutboxConsumer extends AbstractKafkaConsumer {

    private static final String CONSUMER_GROUP = "relation-outbox-consumer";
    private static final String IDEMPOTENCY_SCOPE = "relation";

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
            Long eventId = OutboxMessageUtil.extractEventId(row);
            if (eventId != null && idempotencyGuard.isAlreadyConsumed(IDEMPOTENCY_SCOPE, eventId)) {
                continue;
            }

            JsonNode payloadNode = row.get("payload");
            if (payloadNode == null) {
                continue;
            }

            RelationEvent evt = objectMapper.readValue(payloadNode.asText(), RelationEvent.class);
            processor.process(evt);
            if (eventId != null) {
                idempotencyGuard.markConsumed(IDEMPOTENCY_SCOPE, eventId);
            }
        }
    }

    @Override
    protected String consumerName() {
        return CONSUMER_GROUP;
    }
}
