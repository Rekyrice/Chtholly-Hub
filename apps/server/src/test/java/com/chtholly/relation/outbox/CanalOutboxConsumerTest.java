package com.chtholly.relation.outbox;

import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.chtholly.common.kafka.idempotency.OutboxIdempotencyGuard;
import com.chtholly.relation.event.RelationEvent;
import com.chtholly.relation.processor.RelationEventProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CanalOutboxConsumerTest {

    @Mock
    private RelationEventProcessor processor;

    @Mock
    private KafkaTemplate<String, String> kafka;

    @Mock
    private DeadLetterMessageService deadLetterMessageService;

    @Mock
    private OutboxIdempotencyGuard idempotencyGuard;

    private ObjectMapper objectMapper;
    private CanalOutboxConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new CanalOutboxConsumer(
                objectMapper, processor, kafka, deadLetterMessageService, idempotencyGuard);
    }

    @Test
    void ignoresNonFollowingOutboxRows() throws Exception {
        consumer.process("canal-outbox", null,
                envelope(42L, "post", "PostPublished", "{\"entity\":\"post\"}"), 0);

        verifyNoInteractions(processor, idempotencyGuard);
    }

    @Test
    void ignoresUnknownFollowingEventTypes() throws Exception {
        consumer.process("canal-outbox", null,
                envelope(42L, "following", "FollowingRebuilt", relationPayload("FollowingRebuilt")), 0);

        verifyNoInteractions(processor, idempotencyGuard);
    }

    @Test
    void processesFollowingRowAndMarksOutboxConsumedAfterProjection() throws Exception {
        RelationEvent event = new RelationEvent("FollowCreated", 11L, 22L, 101L);

        consumer.process("canal-outbox", null,
                envelope(42L, "following", "FollowCreated", relationPayload("FollowCreated")), 0);

        verify(processor).process(event);
        verify(idempotencyGuard).markConsumed("relation", 42L);
    }

    @Test
    void failedProjectionDoesNotMarkOutboxConsumed() throws Exception {
        RelationEvent event = new RelationEvent("FollowCreated", 11L, 22L, 101L);
        doThrow(new IllegalStateException("projection failed")).when(processor).process(event);

        assertThatThrownBy(() -> consumer.process("canal-outbox", null,
                envelope(42L, "following", "FollowCreated", relationPayload("FollowCreated")), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("projection failed");

        verify(idempotencyGuard, never()).markConsumed("relation", 42L);
    }

    @Test
    void rejectsSupportedFollowingRowWithoutEventId() throws Exception {
        String envelope = withoutRowField(
                envelope(42L, "following", "FollowCreated", relationPayload("FollowCreated")), "id");

        assertThatThrownBy(() -> consumer.process("canal-outbox", null, envelope, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event ID");

        verifyNoInteractions(processor, idempotencyGuard);
    }

    @Test
    void rejectsSupportedFollowingRowWithoutPayload() throws Exception {
        String envelope = withoutRowField(
                envelope(42L, "following", "FollowCreated", relationPayload("FollowCreated")), "payload");

        assertThatThrownBy(() -> consumer.process("canal-outbox", null, envelope, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");

        verifyNoInteractions(processor, idempotencyGuard);
    }

    private String relationPayload(String type) throws Exception {
        return objectMapper.writeValueAsString(new RelationEvent(type, 11L, 22L, 101L));
    }

    private String envelope(long id, String aggregateType, String type, String payload) throws Exception {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", id);
        row.put("aggregate_type", aggregateType);
        row.put("type", type);
        row.put("payload", payload);
        ArrayNode data = objectMapper.createArrayNode().add(row);
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("table", "outbox");
        envelope.put("type", "INSERT");
        envelope.set("data", data);
        return objectMapper.writeValueAsString(envelope);
    }

    private String withoutRowField(String envelope, String field) throws Exception {
        ObjectNode root = (ObjectNode) objectMapper.readTree(envelope);
        ((ObjectNode) root.withArray("data").get(0)).remove(field);
        return objectMapper.writeValueAsString(root);
    }
}
