package com.chtholly.integration;

import com.chtholly.counter.event.CounterEvent;
import com.chtholly.counter.event.CounterReactionCommittedEvent;
import com.chtholly.counter.event.CounterReactionLocalAdapter;
import com.chtholly.counter.schema.BitmapShard;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.service.CounterReactionCommandService;
import com.chtholly.counter.service.CounterService;
import com.chtholly.counter.service.impl.CounterCalibrationService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Verifies the post-commit reaction path when Kafka dispatch is disabled. */
@TestPropertySource(properties = {
        "kafka.enabled=false",
        "counter.calibration.enabled=false"
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
        assertListenerDeliveryCount(entityId, userId, 1);

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
