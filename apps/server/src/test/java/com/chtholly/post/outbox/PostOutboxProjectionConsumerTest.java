package com.chtholly.post.outbox;

import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PostOutboxProjectionConsumerTest {

    @Mock private PostOutboxProjectionProcessor projectionProcessor;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private DeadLetterMessageService deadLetterMessageService;

    @Test
    void durablePostRowRunsThroughTheSharedReceiptProcessor() throws Exception {
        PostOutboxProjectionConsumer consumer = consumer();

        consumer.process("canal-outbox", "101", envelope(101L, "PostPublished", 42L), 0);

        verify(projectionProcessor).process(101L, "PostPublished", 42L);
    }

    @Test
    void failedProjectionRemainsUnconsumedForRetry() {
        doThrow(new IllegalStateException("RAG unavailable"))
                .when(projectionProcessor)
                .process(101L, "PostContentConfirmed", 42L);
        PostOutboxProjectionConsumer consumer = consumer();

        assertThatThrownBy(() -> consumer.process(
                "canal-outbox", "101", envelope(101L, "PostContentConfirmed", 42L), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RAG unavailable");

    }

    @Test
    void aggregateIdMismatchFailsClosedBeforeWritingAProjectionReceipt() {
        PostOutboxProjectionConsumer consumer = consumer();

        assertThatThrownBy(() -> consumer.process(
                "canal-outbox", "101", envelope(101L, "PostPublished", 41L, 42L), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match payload id");
    }

    private PostOutboxProjectionConsumer consumer() {
        return new PostOutboxProjectionConsumer(
                new ObjectMapper(),
                projectionProcessor,
                kafkaTemplate,
                deadLetterMessageService);
    }

    private String envelope(long eventId, String eventType, long postId) {
        return envelope(eventId, eventType, postId, postId);
    }

    private String envelope(
            long eventId, String eventType, long aggregateId, long payloadPostId) {
        String eventPayload = "{\"entity\":\"post\",\"op\":\"upsert\",\"id\":"
                + payloadPostId + "}";
        return "{\"table\":\"outbox\",\"type\":\"INSERT\",\"data\":[{\"id\":\""
                + eventId
                + "\",\"aggregate_type\":\"post\",\"aggregate_id\":\""
                + aggregateId
                + "\",\"type\":\""
                + eventType
                + "\",\"payload\":"
                + quote(eventPayload)
                + "}]}";
    }

    private String quote(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
