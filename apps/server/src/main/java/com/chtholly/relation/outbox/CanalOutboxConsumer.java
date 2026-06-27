package com.chtholly.relation.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chtholly.relation.event.RelationEvent;
import com.chtholly.relation.processor.RelationEventProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.chtholly.common.util.OutboxMessageUtil;

/**
 * Canal Outbox 消费者。
 * 职责：消费 Canal 桥接写入的 outbox 主题消息，提取 payload 并反序列化为 RelationEvent，交由处理器落库与更新缓存/计数；使用手动位点确保处理成功语义。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanalOutboxConsumer {

    private static final String CONSUMER_GROUP = "relation-outbox-consumer";

    private final ObjectMapper objectMapper;
    private final RelationEventProcessor processor;
    private final KafkaTemplate<String, String> kafka;

    /**
     * 消费 Canal outbox 消息并转为关系事件处理。
     */
    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = CONSUMER_GROUP)
    public void onMessage(String message, Acknowledgment ack) {
        try {
            List<JsonNode> rows = OutboxMessageUtil.extractRows(objectMapper, message);
            if (rows.isEmpty()) {
                ack.acknowledge();
                return;
            }
            for (JsonNode row : rows) {
                JsonNode payloadNode = row.get("payload");
                if (payloadNode == null) {
                    continue;
                }

                RelationEvent evt = objectMapper.readValue(payloadNode.asText(), RelationEvent.class);
                processor.process(evt);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Relation outbox consumer failed, forwarding to DLQ and acking offset: {}", e.getMessage(), e);
            forwardToDlq(message, e);
            ack.acknowledge();
        }
    }

    private void forwardToDlq(String message, Exception error) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("sourceTopic", OutboxTopics.CANAL_OUTBOX);
            envelope.put("consumerGroup", CONSUMER_GROUP);
            envelope.put("errorType", error.getClass().getName());
            envelope.put("errorMessage", error.getMessage());
            envelope.put("payload", message);
            kafka.send(OutboxTopics.CANAL_OUTBOX_DLQ, objectMapper.writeValueAsString(envelope));
        } catch (Exception dlqError) {
            log.error("Failed to forward relation consumer message to DLQ: {}", dlqError.getMessage(), dlqError);
        }
    }
}
