package com.chtholly.counter.event;

import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CounterReactionOutboxConsumerTest {

    @Mock
    private CounterReactionEventProcessor processor;
    @Mock
    private KafkaTemplate<String, String> kafka;
    @Mock
    private DeadLetterMessageService deadLetterMessageService;

    private ObjectMapper objectMapper;
    private CounterReactionOutboxConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new CounterReactionOutboxConsumer(
                objectMapper, processor, kafka, deadLetterMessageService);
    }

    @Test
    void processesAllReactionRowsInOneSharedCoreBatchAndIgnoresOtherAggregates() throws Exception {
        CounterEvent like = event("41", "7", "like", 1);
        CounterEvent favorite = event("42", "8", "fav", 1);
        String payload = envelope(
                row(41L, "counter_reaction", "CounterReactionChanged",
                        objectMapper.writeValueAsString(like)),
                row(99L, "following", "FollowCreated", "{}"),
                row(42L, "counter_reaction", "CounterReactionChanged",
                        objectMapper.writeValueAsString(favorite)));

        consumer.process("canal-outbox", null, payload, 0);

        verify(processor).process(List.of(like, favorite));
    }

    @Test
    void rejectsMismatchBetweenOutboxRowIdAndStablePayloadEventId() throws Exception {
        CounterEvent event = event("42", "7", "like", 1);
        String payload = envelope(row(
                41L,
                "counter_reaction",
                "CounterReactionChanged",
                objectMapper.writeValueAsString(event)));

        assertThatThrownBy(() -> consumer.process("canal-outbox", null, payload, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event ID");

        verifyNoInteractions(processor);
    }

    @Test
    void rejectsSupportedRowWithoutPayload() throws Exception {
        ObjectNode row = row(41L, "counter_reaction", "CounterReactionChanged", "{}");
        row.remove("payload");

        assertThatThrownBy(() -> consumer.process(
                "canal-outbox", null, envelope(row), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");

        verifyNoInteractions(processor);
    }

    @Test
    void ignoresUnknownReactionEventType() throws Exception {
        consumer.process(
                "canal-outbox",
                null,
                envelope(row(41L, "counter_reaction", "CounterReactionRebuilt", "{}")),
                0);

        verifyNoInteractions(processor);
    }

    private CounterEvent event(String eventId, String entityId, String metric, int delta) {
        return CounterEvent.of(
                eventId,
                "post",
                entityId,
                metric,
                "like".equals(metric) ? 1 : 2,
                42L,
                delta);
    }

    private ObjectNode row(
            long id,
            String aggregateType,
            String type,
            String payload) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", id);
        row.put("aggregate_type", aggregateType);
        row.put("type", type);
        row.put("payload", payload);
        return row;
    }

    private String envelope(ObjectNode... rows) throws Exception {
        ArrayNode data = objectMapper.createArrayNode();
        for (ObjectNode row : rows) {
            data.add(row);
        }
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("table", "outbox");
        envelope.put("type", "INSERT");
        envelope.set("data", data);
        return objectMapper.writeValueAsString(envelope);
    }
}
