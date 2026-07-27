package com.chtholly.search.outbox;

import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.chtholly.common.kafka.idempotency.OutboxIdempotencyGuard;
import com.chtholly.search.index.SearchIndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CanalOutboxConsumerSearchTest {

    @Mock private SearchIndexService searchIndexService;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private DeadLetterMessageService deadLetterMessageService;
    @Mock private OutboxIdempotencyGuard idempotencyGuard;

    @Test
    void authorProfileEventReindexesPublishedPostsAndMarksConsumed() throws Exception {
        CanalOutboxConsumerSearch consumer = consumer();

        consumer.process("canal-outbox", "101", authorEvent(101L, 7L), 0);

        verify(searchIndexService).reindexPublishedPostsByAuthor(7L);
        verify(idempotencyGuard).markConsumed("search", 101L);
    }

    @Test
    void consumedAuthorProfileEventIsIgnored() throws Exception {
        when(idempotencyGuard.isAlreadyConsumed("search", 101L)).thenReturn(true);
        CanalOutboxConsumerSearch consumer = consumer();

        consumer.process("canal-outbox", "101", authorEvent(101L, 7L), 0);

        verify(searchIndexService, never()).reindexPublishedPostsByAuthor(7L);
        verify(idempotencyGuard, never()).markConsumed("search", 101L);
    }

    @Test
    void failedAuthorReindexDoesNotMarkEventConsumed() {
        org.mockito.Mockito.doThrow(new IllegalStateException("ES unavailable"))
                .when(searchIndexService).reindexPublishedPostsByAuthor(7L);
        CanalOutboxConsumerSearch consumer = consumer();

        assertThatThrownBy(() -> consumer.process("canal-outbox", "101", authorEvent(101L, 7L), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ES unavailable");

        verify(idempotencyGuard, never()).markConsumed("search", 101L);
    }

    @Test
    void unrelatedAggregateIsIgnoredBeforeRedisIdempotencyLookup() throws Exception {
        CanalOutboxConsumerSearch consumer = consumer();
        String payload = "{\"entityType\":\"post\",\"entityId\":\"7\",\"metric\":\"like\"}";
        String envelope = "{\"table\":\"outbox\",\"type\":\"INSERT\",\"data\":[{"
                + "\"id\":\"102\",\"aggregate_type\":\"counter_reaction\","
                + "\"payload\":" + quote(payload) + "}]}";

        consumer.process("canal-outbox", "102", envelope, 0);

        verifyNoInteractions(idempotencyGuard, searchIndexService);
    }

    private CanalOutboxConsumerSearch consumer() {
        return new CanalOutboxConsumerSearch(
                new ObjectMapper(), searchIndexService, kafkaTemplate, deadLetterMessageService, idempotencyGuard);
    }

    private String authorEvent(long eventId, long userId) {
        String payload = "{\"entity\":\"user\",\"op\":\"author_profile_changed\",\"id\":" + userId + "}";
        return "{\"table\":\"outbox\",\"type\":\"INSERT\",\"data\":[{\"id\":\""
                + eventId + "\",\"aggregate_type\":\"user\",\"payload\":"
                + quote(payload) + "}]}";
    }

    private String quote(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
