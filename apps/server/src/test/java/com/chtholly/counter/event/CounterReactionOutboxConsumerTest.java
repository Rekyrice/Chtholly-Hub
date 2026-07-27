package com.chtholly.counter.event;

import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.chtholly.counter.mapper.CounterReactionKey;
import com.chtholly.counter.service.impl.CounterReactionProjectionStore.ProjectionBatchException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
    void processesValidPeersBeforeRejectingAPoisonedReactionRow() throws Exception {
        CounterEvent poisoned = event("99", "7", "like", 1);
        CounterEvent valid = event("42", "8", "fav", 1);
        String payload = envelope(
                row(41L, "counter_reaction", "CounterReactionChanged",
                        objectMapper.writeValueAsString(poisoned)),
                row(42L, "counter_reaction", "CounterReactionChanged",
                        objectMapper.writeValueAsString(valid)));

        assertThatThrownBy(() -> consumer.process("canal-outbox", null, payload, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event ID");

        verify(processor).process(List.of(valid));
    }

    @Test
    void keyedProjectionFailureKeepsAllHealthyEnvelopePeersInOneBatch() throws Exception {
        CounterEvent poisoned = event("41", "7", "like", 1);
        CounterEvent validFavorite = event("42", "8", "fav", 1);
        CounterEvent validLike = event("43", "9", "like", 1);
        CounterEvent anotherFavorite = event("44", "10", "fav", 1);
        List<CounterEvent> all =
                List.of(poisoned, validFavorite, validLike, anotherFavorite);
        List<CounterEvent> healthy =
                List.of(validFavorite, validLike, anotherFavorite);
        String payload = envelope(
                row(41L, "counter_reaction", "CounterReactionChanged",
                        objectMapper.writeValueAsString(poisoned)),
                row(42L, "counter_reaction", "CounterReactionChanged",
                        objectMapper.writeValueAsString(validFavorite)),
                row(43L, "counter_reaction", "CounterReactionChanged",
                        objectMapper.writeValueAsString(validLike)),
                row(44L, "counter_reaction", "CounterReactionChanged",
                        objectMapper.writeValueAsString(anotherFavorite)));
        ProjectionBatchException failure = new ProjectionBatchException(
                java.util.Set.of(new CounterReactionKey(
                        "post", "7", "like", 42L)));
        doThrow(failure).when(processor).process(all);

        assertThatThrownBy(() -> consumer.process("canal-outbox", null, payload, 0))
                .isSameAs(failure);

        var order = inOrder(processor);
        order.verify(processor).process(all);
        order.verify(processor).process(healthy);
    }

    @Test
    void infrastructureFailureFailsTheWholeEnvelopeWithoutRetryAmplification()
            throws Exception {
        List<CounterEvent> events = events(4);
        String payload = envelope(events.stream()
                .map(event -> {
                    try {
                        return row(
                                Long.parseLong(event.getEventId()),
                                "counter_reaction",
                                "CounterReactionChanged",
                                objectMapper.writeValueAsString(event));
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .toArray(JsonNode[]::new));
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("redis unavailable");
        doThrow(failure).when(processor).process(events);

        assertThatThrownBy(() -> consumer.process("canal-outbox", null, payload, 0))
                .isSameAs(failure);

        verify(processor).process(events);
    }

    @Test
    void unattributedProcessorFailureIsNotAmplified()
            throws Exception {
        List<CounterEvent> events = events(32);
        String payload = envelope(events.stream()
                .map(event -> {
                    try {
                        return row(
                                Long.parseLong(event.getEventId()),
                                "counter_reaction",
                                "CounterReactionChanged",
                                objectMapper.writeValueAsString(event));
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .toArray(JsonNode[]::new));
        IllegalStateException failure =
                new IllegalStateException("unattributed processor failure");
        doThrow(failure).when(processor).process(events);

        assertThatThrownBy(() -> consumer.process("canal-outbox", null, payload, 0))
                .isSameAs(failure);

        verify(processor).process(events);
    }

    @Test
    void processesValidPeersBeforeRejectingMalformedOutboxRows() throws Exception {
        CounterEvent valid = event("42", "8", "fav", 1);
        ObjectNode missingAggregateType =
                row(43L, "following", "FollowCreated", "{}");
        missingAggregateType.remove("aggregate_type");
        ObjectNode missingType =
                row(44L, "following", "FollowCreated", "{}");
        missingType.remove("type");
        String payload = envelope(
                objectMapper.getNodeFactory().numberNode(41L),
                missingAggregateType,
                missingType,
                row(42L, "counter_reaction", "CounterReactionChanged",
                        objectMapper.writeValueAsString(valid)));

        assertThatThrownBy(() -> consumer.process("canal-outbox", null, payload, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Outbox row");

        verify(processor).process(List.of(valid));
    }

    @Test
    void rejectsAnEmptyOutboxChangeSet() throws Exception {
        assertThatThrownBy(() ->
                consumer.process("canal-outbox", null, envelope(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain at least one row");

        verifyNoInteractions(processor);
    }

    @Test
    void rejectsMalformedCanalEnvelopeInsteadOfAcknowledgingIt() {
        assertThatThrownBy(() -> consumer.process(
                "canal-outbox", null, "{\"table\":\"outbox\",", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Canal");

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

    @Test
    void registersConsumerOnlyForTheCompleteCanalKafkaTransport() {
        transportContext("kafka.enabled=true", "canal.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(CounterReactionOutboxConsumer.class));
        transportContext("kafka.enabled=false", "canal.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(CounterReactionOutboxConsumer.class));
        transportContext("kafka.enabled=true", "canal.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(CounterReactionOutboxConsumer.class));
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

    private List<CounterEvent> events(int count) {
        List<CounterEvent> events = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long id = 100L + index;
            events.add(event(
                    Long.toString(id),
                    Long.toString(1000L + index),
                    index % 2 == 0 ? "like" : "fav",
                    1));
        }
        return List.copyOf(events);
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

    private String envelope(JsonNode... rows) throws Exception {
        ArrayNode data = objectMapper.createArrayNode();
        for (JsonNode row : rows) {
            data.add(row);
        }
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("table", "outbox");
        envelope.put("type", "INSERT");
        envelope.set("data", data);
        return objectMapper.writeValueAsString(envelope);
    }

    private ApplicationContextRunner transportContext(String... properties) {
        return new ApplicationContextRunner()
                .withBean(ObjectMapper.class, () -> objectMapper)
                .withBean(CounterReactionEventProcessor.class, () -> processor)
                .withBean(KafkaTemplate.class, () -> kafka)
                .withBean(DeadLetterMessageService.class, () -> deadLetterMessageService)
                .withUserConfiguration(CounterReactionOutboxConsumer.class)
                .withPropertyValues(properties);
    }
}
