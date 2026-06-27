package com.chtholly.search.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chtholly.relation.outbox.OutboxTopics;
import com.chtholly.common.util.OutboxMessageUtil;
import com.chtholly.search.index.SearchIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索索引的 Outbox 消费者：监听 canal-outbox，驱动 ES 索引的增量更新。
 * 仅处理 entity=post 的 upsert 与软删。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanalOutboxConsumerSearch {

    private static final String CONSUMER_GROUP = "search-index-consumer";

    private final ObjectMapper objectMapper;
    private final SearchIndexService indexService;
    private final KafkaTemplate<String, String> kafka;

    /**
     * 消费 outbox 消息，解析合法行并按实体类型更新索引。
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

                JsonNode payload = objectMapper.readTree(payloadNode.asText());
                String entity = text(payload.get("entity"));
                String op = text(payload.get("op"));
                Long id = asLong(payload.get("id"));
                if (!"post".equals(entity) || id == null) {
                    continue;
                }

                if ("delete".equalsIgnoreCase(op)) {
                    indexService.softDeletePost(id);
                } else {
                    indexService.upsertPost(id);
                }
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Search index consumer failed, forwarding to DLQ and acking offset: {}", e.getMessage(), e);
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
            log.error("Failed to forward search consumer message to DLQ: {}", dlqError.getMessage(), dlqError);
        }
    }

    private String text(JsonNode n) {
        return n == null ? null : n.asText();
    }

    private Long asLong(JsonNode n) {
        if (n == null) {
            return null;
        }

        try {
            return Long.parseLong(n.asText());
        } catch (Exception e) {
            log.warn("Invalid outbox id node: {}", n);
            return null;
        }
    }
}
