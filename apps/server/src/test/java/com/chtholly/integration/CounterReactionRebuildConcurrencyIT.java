package com.chtholly.integration;

import com.chtholly.counter.event.CounterEvent;
import com.chtholly.counter.schema.BitmapShard;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.counter.service.CounterReactionCommandService;
import com.chtholly.counter.service.CounterService;
import com.chtholly.counter.service.impl.CounterCalibrationService;
import com.chtholly.counter.service.impl.CounterReactionProjectionRebuilder;
import com.chtholly.counter.service.impl.CounterReactionProjectionStore;
import com.chtholly.relation.outbox.OutboxTopics;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/** Verifies command/rebuild ordering against real MySQL locks, Redis, and Kafka. */
@TestPropertySource(properties = "counter.calibration.enabled=false")
class CounterReactionRebuildConcurrencyIT extends AbstractGoldenPathIT {

    @Autowired
    private CounterService counterService;

    @Autowired
    private CounterCalibrationService calibrationService;

    @SpyBean
    private CounterReactionProjectionRebuilder projectionRebuilder;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @BeforeEach
    void resetState() {
        cleanRedis();
        cleanDatabase();
    }

    @Test
    void likeCommandWaitsForCalibrationSnapshotLockAndUsesNewEpoch() throws Exception {
        assertReactionCommandWaitsForCalibration("7311", 47L, true);
    }

    @Test
    void unlikeCommandWaitsForCalibrationSnapshotLockAndUsesNewEpoch() throws Exception {
        assertReactionCommandWaitsForCalibration("7312", 48L, false);
    }

    private void assertReactionCommandWaitsForCalibration(
            String entityId,
            long userId,
            boolean targetActive) throws Exception {
        long initialCount = targetActive ? 0L : 1L;
        long finalCount = targetActive ? 1L : 0L;
        long initialEpoch = 4L;
        long nextEpoch = 5L;
        if (!targetActive) {
            jdbc.update("""
                    INSERT INTO counter_reaction
                        (entity_type, entity_id, metric, user_id, created_at)
                    VALUES ('post', ?, 'like', ?, NOW(3))
                    """, entityId, userId);
        }
        jdbc.update("""
                INSERT INTO counter_snapshot
                    (entity_type, entity_id, metric, count_value, fact_epoch, updated_at)
                VALUES
                    ('post', ?, 'like', ?, ?, NOW(3)),
                    ('post', ?, 'fav', 0, ?, NOW(3))
                """, entityId, initialCount, initialEpoch, entityId, initialEpoch);

        CountDownLatch rebuildEntered = new CountDownLatch(1);
        CountDownLatch releaseRebuild = new CountDownLatch(1);
        CountDownLatch commandStarted = new CountDownLatch(1);
        AtomicReference<Thread> calibrationThread = new AtomicReference<>();
        AtomicReference<String> rebuildToken = new AtomicReference<>();
        AtomicReference<Long> observedEpoch = new AtomicReference<>();
        doAnswer(invocation -> {
            if (Thread.currentThread() != calibrationThread.get()) {
                return invocation.callRealMethod();
            }
            rebuildToken.set(invocation.getArgument(2, String.class));
            observedEpoch.set(invocation.getArgument(3, Long.class));
            rebuildEntered.countDown();
            try {
                if (!releaseRebuild.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release reaction rebuild");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Reaction rebuild probe was interrupted", exception);
            }
            return invocation.callRealMethod();
        }).when(projectionRebuilder).rebuild(
                eq("post"), eq(entityId), anyString(), anyLong());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CounterCalibrationService.ReconciliationResult> calibrationFuture =
                    executor.submit(() -> {
                        Thread owner = Thread.currentThread();
                        calibrationThread.set(owner);
                        try {
                            return calibrationService.reconcileEntity("post", entityId);
                        } finally {
                            calibrationThread.compareAndSet(owner, null);
                        }
                    });

            assertThat(rebuildEntered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(observedEpoch.get()).isEqualTo(nextEpoch);
            assertThat(redis.opsForValue().get(
                    CounterKeys.factMaintenanceFenceKey("post", entityId)))
                    .isEqualTo(rebuildToken.get());
            assertThat(redis.hasKey(
                    CounterKeys.reactionProjectionCompleteKey("post", entityId))).isFalse();

            Future<Boolean> commandFuture = executor.submit(() -> {
                commandStarted.countDown();
                return targetActive
                        ? counterService.like("post", entityId, userId)
                        : counterService.unlike("post", entityId, userId);
            });

            assertThat(commandStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> commandFuture.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(reactionCount(entityId, userId)).isEqualTo(initialCount);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class)).isZero();
            assertThat(snapshotCount(entityId, "like")).isEqualTo(initialCount);
            assertThat(reactionSnapshotEpochs(entityId))
                    .containsExactly(initialEpoch, initialEpoch);

            releaseRebuild.countDown();

            assertThat(calibrationFuture.get(10, TimeUnit.SECONDS))
                    .isEqualTo(new CounterCalibrationService.ReconciliationResult(
                            initialCount, 0L, nextEpoch));
            assertThat(commandFuture.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(reactionCount(entityId, userId)).isEqualTo(finalCount);
            assertThat(snapshotCount(entityId, "like")).isEqualTo(initialCount);
            assertThat(bitCount(entityId, userId)).isEqualTo(initialCount);
            assertThat(readSds(entityId))
                    .containsExactly(0L, initialCount, 0L, 0L, 0L);

            List<ReactionOutbox> outboxes = reactionOutboxes();
            assertThat(outboxes).hasSize(1);
            ReactionOutbox outbox = outboxes.getFirst();
            CounterEvent event = objectMapper.readValue(outbox.payload(), CounterEvent.class);
            assertThat(outbox.aggregateType())
                    .isEqualTo(CounterReactionCommandService.OUTBOX_AGGREGATE_TYPE);
            assertThat(outbox.eventType())
                    .isEqualTo(CounterReactionCommandService.OUTBOX_EVENT_TYPE);
            assertThat(event.getEventId()).isEqualTo(Long.toString(outbox.id()));
            assertThat(event.getEntityType()).isEqualTo("post");
            assertThat(event.getEntityId()).isEqualTo(entityId);
            assertThat(event.getMetric()).isEqualTo("like");
            assertThat(event.getIdx()).isEqualTo(CounterSchema.IDX_LIKE);
            assertThat(event.getUserId()).isEqualTo(userId);
            assertThat(event.getDelta()).isEqualTo(targetActive ? 1 : -1);
            assertThat(event.getFactEpoch()).isEqualTo(nextEpoch);

            send(outbox);
            awaitKafkaConsumerCaughtUp("counter-reaction-outbox");
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(reactionCount(entityId, userId)).isEqualTo(finalCount);
                assertThat(inboxCount(outbox.id())).isEqualTo(1L);
                assertThat(snapshotCount(entityId, "like")).isEqualTo(finalCount);
                assertThat(snapshotCount(entityId, "fav")).isZero();
                assertThat(reactionSnapshotEpochs(entityId))
                        .containsExactly(nextEpoch, nextEpoch);
                assertThat(bitCount(entityId, userId)).isEqualTo(finalCount);
                assertThat(readSds(entityId))
                        .containsExactly(0L, finalCount, 0L, 0L, 0L);
                assertThat(redis.opsForValue().get(
                        CounterKeys.factEpochKey("post", entityId)))
                        .isEqualTo(Long.toString(nextEpoch));
                assertThat(redis.opsForValue().get(
                        CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                        .isEqualTo(CounterReactionProjectionStore.COMPLETE_VERSION);
                assertThat(redis.hasKey(
                        CounterKeys.factMaintenanceFenceKey("post", entityId))).isFalse();
            });
        } finally {
            releaseRebuild.countDown();
            calibrationThread.set(null);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<ReactionOutbox> reactionOutboxes() {
        return jdbc.query("""
                        SELECT id, aggregate_type, type, payload
                        FROM outbox
                        WHERE aggregate_type = ?
                        ORDER BY id
                        """,
                (resultSet, rowNumber) -> new ReactionOutbox(
                        resultSet.getLong("id"),
                        resultSet.getString("aggregate_type"),
                        resultSet.getString("type"),
                        resultSet.getString("payload")),
                CounterReactionCommandService.OUTBOX_AGGREGATE_TYPE);
    }

    private void send(ReactionOutbox outbox) throws Exception {
        String envelope = canalEnvelope(
                outbox.id(),
                outbox.aggregateType(),
                outbox.eventType(),
                outbox.payload());
        kafka.send(OutboxTopics.CANAL_OUTBOX, Long.toString(outbox.id()), envelope)
                .get(10, TimeUnit.SECONDS);
    }

    private long reactionCount(String entityId, long userId) {
        return jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM counter_reaction
                        WHERE entity_type = 'post'
                          AND entity_id = ?
                          AND metric = 'like'
                          AND user_id = ?
                        """,
                Long.class,
                entityId,
                userId);
    }

    private long inboxCount(long outboxId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_event_inbox WHERE event_id = ?",
                Long.class,
                Long.toString(outboxId));
    }

    private long snapshotCount(String entityId, String metric) {
        return jdbc.queryForObject("""
                        SELECT count_value
                        FROM counter_snapshot
                        WHERE entity_type = 'post' AND entity_id = ? AND metric = ?
                        """,
                Long.class,
                entityId,
                metric);
    }

    private List<Long> reactionSnapshotEpochs(String entityId) {
        return jdbc.queryForList("""
                        SELECT fact_epoch
                        FROM counter_snapshot
                        WHERE entity_type = 'post'
                          AND entity_id = ?
                          AND metric IN ('fav', 'like')
                        ORDER BY metric
                        """,
                Long.class,
                entityId);
    }

    private long bitCount(String entityId, long userId) {
        String bitmapKey = CounterKeys.bitmapKey(
                "like", "post", entityId, BitmapShard.chunkOf(userId));
        Long count = redis.execute((RedisCallback<Long>) connection ->
                connection.stringCommands().bitCount(
                        bitmapKey.getBytes(StandardCharsets.UTF_8)));
        return count == null ? 0L : count;
    }

    private List<Long> readSds(String entityId) {
        byte[] key = CounterKeys.sdsKey("post", entityId)
                .getBytes(StandardCharsets.UTF_8);
        byte[] raw = redis.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(key));
        assertThat(raw)
                .isNotNull()
                .hasSize(CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE);
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        return List.of(
                Integer.toUnsignedLong(buffer.getInt()),
                Integer.toUnsignedLong(buffer.getInt()),
                Integer.toUnsignedLong(buffer.getInt()),
                Integer.toUnsignedLong(buffer.getInt()),
                Integer.toUnsignedLong(buffer.getInt()));
    }

    private record ReactionOutbox(
            long id,
            String aggregateType,
            String eventType,
            String payload) {}
}
