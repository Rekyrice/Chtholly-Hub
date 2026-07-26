package com.chtholly.integration;

import com.chtholly.counter.event.CounterEvent;
import com.chtholly.counter.service.CounterReactionCommandService;
import com.chtholly.counter.service.CounterService;
import com.chtholly.relation.outbox.OutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.TransientDataAccessResourceException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** Verifies authoritative reaction facts and their transaction-coupled Outbox rows in MySQL. */
class CounterReactionFactsIT extends AbstractGoldenPathIT {

    @Autowired
    private CounterService counterService;

    @SpyBean
    private OutboxMapper outboxMapper;

    @BeforeEach
    void resetState() {
        reset(outboxMapper);
        cleanRedis();
        cleanDatabase();
    }

    @Test
    void likeAndFavoriteLifecyclesPersistOnlyRealChangesAndOneOutboxPerChange()
            throws Exception {
        String entityId = "7101";
        long userId = 42L;

        assertThat(counterService.like("post", entityId, userId)).isTrue();
        assertThat(counterService.like("post", entityId, userId)).isFalse();
        assertThat(counterService.fav("post", entityId, userId)).isTrue();
        assertThat(counterService.fav("post", entityId, userId)).isFalse();
        assertThat(counterService.unfav("post", entityId, userId)).isTrue();
        assertThat(counterService.unfav("post", entityId, userId)).isFalse();
        assertThat(counterService.unlike("post", entityId, userId)).isTrue();
        assertThat(counterService.unlike("post", entityId, userId)).isFalse();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_reaction WHERE entity_type = 'post' "
                        + "AND entity_id = ? AND user_id = ?",
                Long.class,
                entityId,
                userId)).isZero();
        List<CounterEvent> events = jdbc.queryForList(
                        "SELECT payload FROM outbox WHERE aggregate_type = ? ORDER BY id",
                        String.class,
                        CounterReactionCommandService.OUTBOX_AGGREGATE_TYPE)
                .stream()
                .map(this::readEvent)
                .toList();
        assertThat(events).extracting(CounterEvent::getMetric)
                .containsExactly("like", "fav", "fav", "like");
        assertThat(events).extracting(CounterEvent::getDelta)
                .containsExactly(1, 1, -1, -1);
        assertThat(events).allSatisfy(event ->
                assertThat(event.getEventId()).containsOnlyDigits());
    }

    @Test
    void outboxFailureRollsBackTheRelationAndSnapshotRows() {
        doThrow(new TransientDataAccessResourceException("forced Outbox failure"))
                .when(outboxMapper)
                .insert(
                        anyLong(),
                        anyString(),
                        any(),
                        anyString(),
                        anyString());

        assertThatThrownBy(() -> counterService.like("post", "7102", 43L))
                .isInstanceOf(TransientDataAccessResourceException.class)
                .hasMessageContaining("Outbox");

        assertMysqlCommandTablesAreEmpty();
    }

    @Test
    void relationFailureCannotLeaveSnapshotOrOutboxRows() {
        jdbc.execute("""
                CREATE TRIGGER fail_counter_reaction_insert
                BEFORE INSERT ON counter_reaction
                FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced relation failure'
                """);
        try {
            assertThatThrownBy(() -> counterService.like("post", "7103", 44L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("forced relation failure");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_counter_reaction_insert");
        }

        assertMysqlCommandTablesAreEmpty();
    }

    @Test
    void concurrentSameTargetRequestsCreateOneFactAndOneOutbox() throws Exception {
        String entityId = "7104";
        long userId = 45L;
        int requestCount = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            List<Future<Boolean>> futures = java.util.stream.IntStream
                    .range(0, requestCount)
                    .mapToObj(ignored -> executor.submit(() -> {
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        return counterService.like("post", entityId, userId);
                    }))
                    .toList();
            start.countDown();

            List<Boolean> results = futures.stream().map(future -> {
                try {
                    return future.get(20, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException("Concurrent reaction command failed", exception);
                }
            }).toList();

            assertThat(results).containsOnlyOnce(true);
            assertThat(results).filteredOn(Boolean.FALSE::equals)
                    .hasSize(requestCount - 1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_reaction WHERE entity_type = 'post' "
                        + "AND entity_id = ? AND metric = 'like' AND user_id = ?",
                Long.class,
                entityId,
                userId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_type = ?",
                Long.class,
                CounterReactionCommandService.OUTBOX_AGGREGATE_TYPE)).isEqualTo(1L);
    }

    private CounterEvent readEvent(String payload) {
        try {
            return objectMapper.readValue(payload, CounterEvent.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Counter reaction Outbox payload is invalid", exception);
        }
    }

    private void assertMysqlCommandTablesAreEmpty() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_reaction", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_snapshot", Long.class)).isZero();
    }
}
