package com.chtholly.search.outbox;

import com.chtholly.common.kafka.AbstractKafkaConsumer;
import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.chtholly.common.kafka.idempotency.OutboxIdempotencyGuard;
import com.chtholly.common.util.OutboxMessageUtil;
import com.chtholly.relation.outbox.OutboxTopics;
import com.chtholly.search.index.SearchIndexService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 搜索索引的 Outbox 消费者：监听 canal-outbox，驱动 ES 索引的增量更新。
 * 仅处理 entity=post 的 upsert 与软删。
 */
@Slf4j
@Service
public class CanalOutboxConsumerSearch extends AbstractKafkaConsumer {

    private static final String CONSUMER_GROUP = "search-index-consumer";
    private static final String IDEMPOTENCY_SCOPE = "search";

    private final SearchIndexService indexService;
    private final OutboxIdempotencyGuard idempotencyGuard;

    public CanalOutboxConsumerSearch(ObjectMapper objectMapper,
                                     SearchIndexService indexService,
                                     KafkaTemplate<String, String> kafka,
                                     DeadLetterMessageService deadLetterMessageService,
                                     OutboxIdempotencyGuard idempotencyGuard) {
        super(kafka, objectMapper, deadLetterMessageService);
        this.indexService = indexService;
        this.idempotencyGuard = idempotencyGuard;
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = CONSUMER_GROUP)
    public void onMessage(String message, Acknowledgment ack) {
        consumeMessage(OutboxTopics.CANAL_OUTBOX, null, message, ack);
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX + "-retry", groupId = CONSUMER_GROUP + "-retry")
    public void onRetryMessage(String message, Acknowledgment ack) {
        consumeRetryEnvelope(message, ack);
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

            JsonNode eventPayload = objectMapper.readTree(payloadNode.asText());
            String entity = text(eventPayload.get("entity"));
            String op = text(eventPayload.get("op"));
            Long id = asLong(eventPayload.get("id"));
            if (!"post".equals(entity) || id == null) {
                continue;
            }

            if ("delete".equalsIgnoreCase(op)) {
                indexService.softDeletePost(id);
            } else {
                indexService.upsertPost(id);
            }
            if (eventId != null) {
                idempotencyGuard.markConsumed(IDEMPOTENCY_SCOPE, eventId);
            }
        }
    }

    @Override
    protected String consumerName() {
        return CONSUMER_GROUP;
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
        } catch (NumberFormatException e) {
            log.warn("Invalid outbox id node: {}", n);
            return null;
        }
    }
}
