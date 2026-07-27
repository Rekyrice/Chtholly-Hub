package com.chtholly.integration;

import com.chtholly.counter.event.CounterEvent;
import com.chtholly.counter.event.CounterReactionCommittedEvent;
import com.chtholly.counter.event.CounterReactionEventProcessor;
import com.chtholly.counter.event.CounterReactionLocalAdapter;
import com.chtholly.counter.event.CounterReactionLocalOutboxReplay;
import com.chtholly.counter.mapper.CounterEntityIdentity;
import com.chtholly.counter.mapper.CounterPersistenceMapper;
import com.chtholly.counter.schema.BitmapShard;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.service.CounterFactMaintenanceService;
import com.chtholly.counter.service.CounterReactionCommandService;
import com.chtholly.counter.service.CounterService;
import com.chtholly.counter.service.impl.CounterCalibrationService;
import com.chtholly.counter.service.impl.CounterReactionProjectionRebuilder;
import com.chtholly.counter.service.impl.CounterReactionProjectionStore;
import com.chtholly.notification.listener.NotificationEventListener;
import com.chtholly.post.listener.FeedCacheInvalidationListener;
import com.chtholly.recommendation.UserInterestProfileListener;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Verifies the post-commit reaction path when Kafka dispatch is disabled. */
@TestPropertySource(properties = {
        "kafka.enabled=false",
        "counter.calibration.enabled=false",
        "counter.reaction.local-replay.initial-delay=PT1H"
})
class CounterReactionLocalModeIT extends AbstractGoldenPathIT {

    @Autowired
    private CounterService counterService;

    @Autowired
    private CounterCalibrationService calibrationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CounterReactionLocalAdapter localAdapter;

    @Autowired
    private CounterReactionLocalOutboxReplay localOutboxReplay;

    @Autowired
    private CounterReactionEventProcessor reactionEventProcessor;

    @Autowired
    private CounterFactMaintenanceService factMaintenanceService;

    @Autowired
    private CounterPersistenceMapper persistenceMapper;

    @SpyBean
    private CounterReactionProjectionRebuilder projectionRebuilder;

    @SpyBean
    private CounterReactionProjectionStore projectionStore;

    @SpyBean(proxyTargetAware = true)
    private NotificationEventListener notificationListener;

    @SpyBean
    private FeedCacheInvalidationListener feedListener;

    @SpyBean
    private UserInterestProfileListener profileListener;

    @BeforeEach
    void resetState() {
        cleanRedis();
        cleanDatabase();
        clearInvocations(notificationListener, feedListener, profileListener);
    }

    @Test
    void committedCommandProjectsAndPersistsThroughTheSharedLocalCore() throws Exception {
        String entityId = "7301";
        long userId = 42L;
        assertThat(calibrationService.reconcileEntity("post", entityId))
                .isEqualTo(new CounterCalibrationService.ReconciliationResult(0L, 0L, 1L));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(counterService.like("post", entityId, userId)).isTrue();
            assertThat(counterService.like("post", entityId, userId)).isFalse();
            assertThat(reactionCount(entityId, userId)).isEqualTo(1L);
            assertThat(outboxCount()).isEqualTo(1L);
            assertThat(inboxCount(entityId)).isZero();
            assertThat(snapshotCount(entityId)).isZero();
            assertThat(bitmapState(entityId, userId)).isFalse();
        });

        assertThat(reactionCount(entityId, userId)).isEqualTo(1L);
        assertThat(outboxCount()).isEqualTo(1L);
        assertThat(inboxCount(entityId)).isEqualTo(1L);
        assertThat(snapshotCount(entityId)).isEqualTo(1L);
        assertThat(bitmapState(entityId, userId)).isTrue();
        assertThat(counterService.getCounts("post", entityId, List.of("like")))
                .containsEntry("like", 1L);
        assertThat(redis.opsForValue().get(
                CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                .isEqualTo(CounterReactionProjectionStore.COMPLETE_VERSION);
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertListenerDeliveryCount(entityId, userId, 1));

        CounterEvent event = objectMapper.readValue(jdbc.queryForObject(
                "SELECT payload FROM outbox WHERE aggregate_type = ?",
                String.class,
                CounterReactionCommandService.OUTBOX_AGGREGATE_TYPE), CounterEvent.class);
        localAdapter.onCommitted(new CounterReactionCommittedEvent(event));

        Awaitility.await()
                .during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertListenerDeliveryCount(entityId, userId, 1));
    }

    @Test
    void rolledBackCommandNeverReachesTheLocalProjection() {
        String entityId = "7302";
        long userId = 43L;
        assertThat(calibrationService.reconcileEntity("post", entityId))
                .isEqualTo(new CounterCalibrationService.ReconciliationResult(0L, 0L, 1L));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(counterService.like("post", entityId, userId)).isTrue();
            assertThat(reactionCount(entityId, userId)).isEqualTo(1L);
            assertThat(outboxCount()).isEqualTo(1L);
            assertThat(inboxCount(entityId)).isZero();
            assertThat(snapshotCount(entityId)).isZero();
            assertThat(bitmapState(entityId, userId)).isFalse();
            status.setRollbackOnly();
        });

        assertThat(reactionCount(entityId, userId)).isZero();
        assertThat(outboxCount()).isZero();
        assertThat(inboxCount(entityId)).isZero();
        assertThat(snapshotCount(entityId)).isZero();
        assertThat(bitmapState(entityId, userId)).isFalse();
        assertThat(counterService.getCounts("post", entityId, List.of("like")))
                .containsEntry("like", 0L);
        assertThat(redis.opsForValue().get(
                CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                .isEqualTo(CounterReactionProjectionStore.COMPLETE_VERSION);
    }

    @Test
    void batchCountReadFallsBackToOneGroupedMysqlQueryWhenProjectionIsMissing() {
        jdbc.update("""
                INSERT INTO counter_reaction
                    (entity_type, entity_id, metric, user_id, created_at)
                VALUES
                    ('post', '7310', 'like', 51, NOW(3)),
                    ('post', '7310', 'fav', 52, NOW(3)),
                    ('post', '7311', 'like', 53, NOW(3)),
                    ('post', '7311', 'like', 54, NOW(3))
                """);

        assertThat(counterService.getCountsBatch(
                        "post",
                        List.of("7310", "7311"),
                        List.of("like", "fav")))
                .containsEntry("7310", java.util.Map.of("like", 1L, "fav", 1L))
                .containsEntry("7311", java.util.Map.of("like", 2L, "fav", 0L));
    }

    @Test
    void singleCountReadFallsBackToOneGroupedMysqlSnapshotWhenProjectionIsMissing() {
        jdbc.update("""
                INSERT INTO counter_reaction
                    (entity_type, entity_id, metric, user_id, created_at)
                VALUES
                    ('post', '7312', 'like', 51, NOW(3)),
                    ('post', '7312', 'fav', 52, NOW(3))
                """);

        assertThat(counterService.getCounts(
                "post", "7312", List.of("like", "fav")))
                .containsExactly(
                        java.util.Map.entry("like", 1L),
                        java.util.Map.entry("fav", 1L));
    }

    @Test
    void maintenanceFactsAndProjectionPublishShareOneReentrantEntityLock() {
        CounterFactMaintenanceService.ReactionReconciliationResult result =
                factMaintenanceService.reconcileManagedPostReactions(
                        java.util.Set.of(51L, 52L),
                        java.util.Set.of(7313L),
                        java.util.Map.of(
                                7313L,
                                new CounterFactMaintenanceService.ManagedPostReactionState(
                                        java.util.Set.of(51L),
                                        java.util.Set.of(52L))));

        assertThat(result.posts().get(7313L))
                .isEqualTo(new CounterFactMaintenanceService.PostReactionReconciliationResult(
                        7313L, 2L, 0L, 1L, 1L));
        assertThat(counterService.isLiked("post", "7313", 51L)).isTrue();
        assertThat(counterService.isFaved("post", "7313", 52L)).isTrue();
        assertThat(counterService.getCounts(
                "post", "7313", List.of("like", "fav")))
                .containsExactly(
                        java.util.Map.entry("like", 1L),
                        java.util.Map.entry("fav", 1L));
        assertThat(outboxCount()).isZero();
    }

    @Test
    void scheduledCalibrationMapperUsesAStableBoundedKeysetPage() {
        jdbc.update("""
                INSERT INTO counter_snapshot
                    (entity_type, entity_id, metric, count_value, fact_epoch, updated_at)
                VALUES
                    ('post', '7402', 'like', 0, 0, NOW(3)),
                    ('post', '7401', 'like', 0, 0, NOW(3))
                """);

        assertThat(persistenceMapper.findReactionSnapshotIdentityHighWatermark())
                .isEqualTo(new CounterEntityIdentity("post", "7402"));
        assertThat(persistenceMapper.listReactionSnapshotIdentitiesPage(
                null, null, "post", "7402", 1))
                .containsExactly(new CounterEntityIdentity("post", "7401"));
        assertThat(persistenceMapper.listReactionSnapshotIdentitiesPage(
                "post", "7401", "post", "7402", 1))
                .containsExactly(new CounterEntityIdentity("post", "7402"));
    }

    @Test
    void callerManagedTransactionCannotPublishAReconciliationEarly() {
        String entityId = "7303";

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThatThrownBy(() ->
                        calibrationService.reconcileEntity("post", entityId))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("active transaction"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_snapshot WHERE entity_id = ?",
                Long.class,
                entityId)).isZero();
        assertThat(redis.hasKey(
                CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                .isFalse();
        assertThat(redis.hasKey(
                CounterKeys.factMaintenanceFenceKey("post", entityId)))
                .isFalse();
    }

    @Test
    void eventInCommitToPublishWindowDirtiesRebuildWithoutLosingDurableWork()
            throws Exception {
        String entityId = "7304";
        long userId = 44L;
        assertThat(calibrationService.reconcileEntity("post", entityId))
                .isEqualTo(new CounterCalibrationService.ReconciliationResult(0L, 0L, 1L));

        CountDownLatch publishEntered = new CountDownLatch(1);
        CountDownLatch releasePublish = new CountDownLatch(1);
        AtomicReference<Thread> calibrationOwner = new AtomicReference<>();
        doAnswer(invocation -> {
            if (Thread.currentThread() == calibrationOwner.get()) {
                publishEntered.countDown();
                if (!releasePublish.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timed out waiting to release reconciliation publication");
                }
            }
            return invocation.callRealMethod();
        }).when(projectionRebuilder).publishComplete(
                eq("post"), eq(entityId), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CounterCalibrationService.ReconciliationResult> reconciliation =
                    executor.submit(() -> {
                        Thread owner = Thread.currentThread();
                        calibrationOwner.set(owner);
                        try {
                            return calibrationService.reconcileEntity("post", entityId);
                        } finally {
                            calibrationOwner.compareAndSet(owner, null);
                        }
                    });

            assertThat(publishEntered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> command =
                    executor.submit(() -> counterService.like("post", entityId, userId));

            assertThat(command.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(reactionCount(entityId, userId)).isEqualTo(1L);
            assertThat(inboxCount(entityId)).isZero();
            assertThat(snapshotCount(entityId)).isZero();
            assertThat(redis.opsForValue().get(
                    CounterKeys.factMaintenanceFenceKey("post", entityId)))
                    .startsWith("@dirty:");
            assertThat(redis.hasKey(
                    CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                    .isFalse();
            assertListenerDeliveryCount(entityId, userId, 0);

            releasePublish.countDown();

            assertThatThrownBy(() -> reconciliation.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(RuntimeException.class);
            assertThat(redis.hasKey(
                    CounterKeys.factMaintenanceFenceKey("post", entityId)))
                    .isFalse();
            assertThat(redis.hasKey(
                    CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                    .isFalse();
            assertThat(bitmapState(entityId, userId)).isFalse();

            jdbc.update(
                    "UPDATE outbox SET created_at = TIMESTAMPADD(SECOND, -10, NOW(3)) "
                            + "WHERE aggregate_type = ?",
                    CounterReactionCommandService.OUTBOX_AGGREGATE_TYPE);
            localOutboxReplay.replayPending();
            assertThat(inboxCount(entityId)).isEqualTo(1L);
            assertThat(snapshotCount(entityId)).isEqualTo(1L);
            assertThat(counterService.isLiked("post", entityId, userId)).isTrue();
            assertThat(counterService.getCounts("post", entityId, List.of("like")))
                    .containsEntry("like", 1L);

            assertThat(calibrationService.reconcileEntity("post", entityId))
                    .isEqualTo(
                            new CounterCalibrationService.ReconciliationResult(1L, 0L, 3L));
            assertThat(bitmapState(entityId, userId)).isTrue();
            assertThat(redis.opsForValue().get(
                    CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                    .isEqualTo(CounterReactionProjectionStore.COMPLETE_VERSION);
            assertListenerDeliveryCount(entityId, userId, 1);
        } finally {
            releasePublish.countDown();
            calibrationOwner.set(null);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentLocalEventsCannotReapplyAnOlderTerminalState() throws Exception {
        String entityId = "7305";
        long userId = 45L;
        assertThat(calibrationService.reconcileEntity("post", entityId))
                .isEqualTo(new CounterCalibrationService.ReconciliationResult(0L, 0L, 1L));

        CountDownLatch likeProjectionEntered = new CountDownLatch(1);
        CountDownLatch releaseLikeProjection = new CountDownLatch(1);
        AtomicReference<Thread> likeOwner = new AtomicReference<>();
        doAnswer(invocation -> {
            if (Thread.currentThread() == likeOwner.get()) {
                likeProjectionEntered.countDown();
                if (!releaseLikeProjection.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timed out waiting to release the first projection");
                }
            }
            return invocation.callRealMethod();
        }).when(projectionStore).project(argThat(targets ->
                targets.keySet().stream().anyMatch(key ->
                        entityId.equals(key.entityId())
                                && key.userId() == userId)));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> like = executor.submit(() -> {
                Thread owner = Thread.currentThread();
                likeOwner.set(owner);
                try {
                    return counterService.like("post", entityId, userId);
                } finally {
                    likeOwner.compareAndSet(owner, null);
                }
            });
            assertThat(likeProjectionEntered.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> unlike =
                    executor.submit(() -> counterService.unlike("post", entityId, userId));
            assertThatThrownBy(() -> unlike.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseLikeProjection.countDown();

            assertThat(like.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(unlike.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(reactionCount(entityId, userId)).isZero();
            assertThat(inboxCount(entityId)).isEqualTo(2L);
            assertThat(snapshotCount(entityId)).isZero();
            assertThat(bitmapState(entityId, userId)).isFalse();
            assertThat(counterService.isLiked("post", entityId, userId)).isFalse();
            assertThat(counterService.getCounts("post", entityId, List.of("like")))
                    .containsEntry("like", 0L);
            assertThat(redis.opsForValue().get(
                    CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                    .isEqualTo(CounterReactionProjectionStore.COMPLETE_VERSION);
        } finally {
            releaseLikeProjection.countDown();
            likeOwner.set(null);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentReplayPublishesOneSideEffectBatchUnderTheDurableInboxClaim()
            throws Exception {
        String entityId = "7307";
        long userId = 47L;
        String eventId = "9007307";
        assertThat(calibrationService.reconcileEntity("post", entityId))
                .isEqualTo(new CounterCalibrationService.ReconciliationResult(0L, 0L, 1L));
        jdbc.update("""
                INSERT INTO counter_reaction
                    (entity_type, entity_id, metric, user_id, created_at)
                VALUES ('post', ?, 'like', ?, NOW(3))
                """, entityId, userId);
        CounterEvent event = CounterEvent.of(
                eventId, "post", entityId, "like", 1, userId, 1);
        event.setFactEpoch(1L);

        CountDownLatch firstPublicationEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstPublication = new CountDownLatch(1);
        AtomicBoolean blockFirstPublication = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (blockFirstPublication.compareAndSet(true, false)) {
                firstPublicationEntered.countDown();
                if (!releaseFirstPublication.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timed out waiting to release side-effect publication");
                }
            }
            return invocation.callRealMethod();
        }).when(feedListener).onCounterChanged(argThat(candidate ->
                candidate != null && eventId.equals(candidate.getEventId())));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first =
                    executor.submit(() -> reactionEventProcessor.process(List.of(event)));
            assertThat(firstPublicationEntered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> replay =
                    executor.submit(() -> reactionEventProcessor.process(List.of(event)));

            assertThatThrownBy(() -> replay.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseFirstPublication.countDown();
            first.get(10, TimeUnit.SECONDS);
            replay.get(10, TimeUnit.SECONDS);

            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() ->
                            assertListenerDeliveryCount(entityId, userId, 1));
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM counter_event_inbox "
                                    + "WHERE event_id = ? "
                                    + "AND side_effects_published_at IS NOT NULL",
                            Long.class,
                            eventId))
                    .isEqualTo(1L);
        } finally {
            releaseFirstPublication.countDown();
            blockFirstPublication.set(false);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void localOutboxReplayDeliversARealTransitionAbsorbedByANewerFactEpoch()
            throws Exception {
        String entityId = "7306";
        long userId = 46L;
        long outboxId = 9_007_306L;
        assertThat(calibrationService.reconcileEntity("post", entityId))
                .isEqualTo(new CounterCalibrationService.ReconciliationResult(0L, 0L, 1L));

        CounterEvent event = CounterEvent.of(
                Long.toString(outboxId),
                "post",
                entityId,
                "like",
                1,
                userId,
                1);
        event.setFactEpoch(1L);
        jdbc.update("""
                INSERT INTO counter_reaction
                    (entity_type, entity_id, metric, user_id, created_at)
                VALUES ('post', ?, 'like', ?, NOW(3))
                """, entityId, userId);
        jdbc.update("""
                INSERT INTO outbox
                    (id, aggregate_type, aggregate_id, type, payload, created_at)
                VALUES (?, ?, ?, ?, CAST(? AS JSON), TIMESTAMPADD(SECOND, -10, NOW(3)))
                """,
                outboxId,
                CounterReactionCommandService.OUTBOX_AGGREGATE_TYPE,
                userId,
                CounterReactionCommandService.OUTBOX_EVENT_TYPE,
                objectMapper.writeValueAsString(event));

        assertThat(calibrationService.reconcileEntity("post", entityId))
                .isEqualTo(new CounterCalibrationService.ReconciliationResult(1L, 0L, 2L));
        localOutboxReplay.replayPending();

        assertThat(inboxCount(entityId)).isEqualTo(1L);
        assertThat(snapshotCount(entityId)).isEqualTo(1L);
        assertThat(bitmapState(entityId, userId)).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM counter_event_inbox "
                        + "WHERE event_id = ? AND side_effects_published_at IS NOT NULL",
                Long.class,
                Long.toString(outboxId))).isEqualTo(1L);
        assertListenerDeliveryCount(entityId, userId, 1);

        localOutboxReplay.replayPending();
        assertListenerDeliveryCount(entityId, userId, 1);
    }

    private long reactionCount(String entityId, long userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_reaction WHERE entity_type = 'post' "
                        + "AND entity_id = ? AND metric = 'like' AND user_id = ?",
                Long.class,
                entityId,
                userId);
    }

    private long outboxCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_type = ?",
                Long.class,
                CounterReactionCommandService.OUTBOX_AGGREGATE_TYPE);
    }

    private long inboxCount(String entityId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_event_inbox WHERE entity_id = ?",
                Long.class,
                entityId);
    }

    private long snapshotCount(String entityId) {
        return jdbc.queryForObject(
                "SELECT count_value FROM counter_snapshot "
                        + "WHERE entity_type = 'post' AND entity_id = ? AND metric = 'like'",
                Long.class,
                entityId);
    }

    private boolean bitmapState(String entityId, long userId) {
        return Boolean.TRUE.equals(redis.opsForValue().getBit(
                CounterKeys.bitmapKey(
                        "like", "post", entityId, BitmapShard.chunkOf(userId)),
                BitmapShard.bitOf(userId)));
    }

    private void assertListenerDeliveryCount(String entityId, long userId, int count) {
        verify(notificationListener, times(count)).onCounterEvent(argThat(event ->
                isExpectedLike(event, entityId, userId)));
        verify(feedListener, times(count)).onCounterChanged(argThat(event ->
                isExpectedLike(event, entityId, userId)));
        verify(profileListener, times(count)).onCounterEvent(argThat(event ->
                isExpectedLike(event, entityId, userId)));
    }

    private static boolean isExpectedLike(CounterEvent event, String entityId, long userId) {
        return event != null
                && "post".equals(event.getEntityType())
                && entityId.equals(event.getEntityId())
                && "like".equals(event.getMetric())
                && event.getUserId() == userId
                && event.getDelta() == 1;
    }
}
