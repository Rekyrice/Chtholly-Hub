package com.chtholly.integration;

import com.chtholly.counter.event.CounterAggregationProcessor;
import com.chtholly.counter.event.CounterEvent;
import com.chtholly.counter.event.CounterTopics;
import com.chtholly.counter.schema.BitmapShard;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.counter.service.CounterService;
import com.chtholly.counter.service.CounterReactionCommandService;
import com.chtholly.counter.service.impl.CounterCalibrationService;
import com.chtholly.counter.service.impl.CounterReactionProjectionStore;
import com.chtholly.relation.outbox.OutboxTopics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.anyList;

/** Verifies the counter path against real Redis, Kafka, and MySQL instances. */
class CounterGoldenPathIT extends AbstractGoldenPathIT {

    private static final String AGGREGATION_GROUP = "counter-agg";

    @Autowired
    private CounterService counterService;

    @SpyBean
    private CounterAggregationProcessor aggregationProcessor;

    @Autowired
    private CounterCalibrationService calibrationService;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerRegistry;

    @BeforeEach
    void resetState() {
        cleanRedis();
        cleanDatabase();
    }

    @Test
    void duplicateCanalOutboxDeliveryConvergesToOneFactSnapshotAndBitmapBit() throws Exception {
        String entityId = "7001";
        long userId = 42L;

        assertThat(calibrationService.reconcileEntity("post", entityId))
                .isEqualTo(new CounterCalibrationService.ReconciliationResult(0L, 0L, 1L));
        assertThat(counterService.like("post", entityId, userId)).isTrue();
        assertThat(counterService.like("post", entityId, userId)).isFalse();
        Map<String, Object> outbox = jdbc.queryForMap("""
                SELECT id, aggregate_type, type, payload
                FROM outbox
                WHERE aggregate_type = ?
                """, CounterReactionCommandService.OUTBOX_AGGREGATE_TYPE);
        long outboxId = ((Number) outbox.get("id")).longValue();
        String payload = String.valueOf(outbox.get("payload"));
        CounterEvent emitted = objectMapper.readValue(payload, CounterEvent.class);
        String envelope = canalEnvelope(
                outboxId,
                String.valueOf(outbox.get("aggregate_type")),
                String.valueOf(outbox.get("type")),
                payload);

        kafka.send(OutboxTopics.CANAL_OUTBOX, Long.toString(outboxId), envelope)
                .get(10, TimeUnit.SECONDS);
        kafka.send(OutboxTopics.CANAL_OUTBOX, Long.toString(outboxId), envelope)
                .get(10, TimeUnit.SECONDS);
        awaitKafkaConsumerCaughtUp("counter-reaction-outbox");

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(bitCount("like", entityId, userId)).isEqualTo(1L));
        assertThat(readSds(entityId).get(CounterSchema.IDX_LIKE)).isEqualTo(1L);
        assertThat(counterService.isLiked("post", entityId, userId)).isTrue();
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
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_event_inbox WHERE event_id = ?",
                Long.class,
                emitted.getEventId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count_value FROM counter_snapshot "
                        + "WHERE entity_type = 'post' AND entity_id = ? AND metric = 'like'",
                Long.class,
                entityId)).isEqualTo(1L);
        assertThat(redis.opsForHash().entries(CounterKeys.aggKey("post", entityId))).isEmpty();
    }

    @Test
    void redisProjectionFailureIsRetriedFromCanalOutboxAndConverges() throws Exception {
        String entityId = "7013";
        long userId = 45L;
        assertThat(calibrationService.reconcileEntity("post", entityId))
                .isEqualTo(new CounterCalibrationService.ReconciliationResult(0L, 0L, 1L));
        assertThat(counterService.like("post", entityId, userId)).isTrue();
        ReactionOutbox outbox = reactionOutboxes().getFirst();

        try {
            REDIS_PROXY.setConnectionCut(true);
            send(outbox);
            Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(initialRetryFailureCount(outbox.id())).isEqualTo(1L);
                assertThat(inboxCount(outbox.id())).isZero();
                assertThat(snapshotCount(entityId, "like")).isZero();
            });
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(inboxCount(outbox.id())).isEqualTo(1L);
            assertThat(snapshotCount(entityId, "like")).isEqualTo(1L);
            assertThat(bitCount("like", entityId, userId)).isEqualTo(1L);
            assertThat(counterService.getCounts("post", entityId, List.of("like")))
                    .containsEntry("like", 1L);
            assertThat(redis.opsForValue().get(
                    CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                    .isEqualTo(CounterReactionProjectionStore.COMPLETE_VERSION);
        });
        assertThat(counterService.isLiked("post", entityId, userId)).isTrue();
        assertThat(initialRetryFailureCount(outbox.id())).isEqualTo(1L);
        awaitKafkaConsumerCaughtUp("counter-reaction-outbox");
        awaitKafkaConsumerCaughtUp("counter-reaction-outbox-retry");
    }

    @Test
    void mysqlAggregationFailureAfterProjectionRetriesWithoutRedisDoubleCount() throws Exception {
        String entityId = "7014";
        long userId = 46L;
        String trigger = "fail_counter_reaction_snapshot_update";
        assertThat(calibrationService.reconcileEntity("post", entityId))
                .isEqualTo(new CounterCalibrationService.ReconciliationResult(0L, 0L, 1L));
        assertThat(counterService.like("post", entityId, userId)).isTrue();
        ReactionOutbox outbox = reactionOutboxes().getFirst();
        jdbc.execute("""
                CREATE TRIGGER fail_counter_reaction_snapshot_update
                BEFORE UPDATE ON counter_snapshot
                FOR EACH ROW
                BEGIN
                    IF NEW.entity_type = 'post'
                       AND NEW.entity_id = '7014'
                       AND NEW.metric = 'like'
                       AND NEW.count_value <> OLD.count_value THEN
                        SIGNAL SQLSTATE '45000'
                            SET MESSAGE_TEXT = 'forced snapshot failure';
                    END IF;
                END
                """);
        try {
            send(outbox);
            Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(initialRetryFailureCount(outbox.id())).isEqualTo(1L);
                assertThat(inboxCount(outbox.id())).isZero();
                assertThat(snapshotCount(entityId, "like")).isZero();
                assertThat(bitCount("like", entityId, userId)).isEqualTo(1L);
                assertThat(counterService.getCounts("post", entityId, List.of("like")))
                        .containsEntry("like", 1L);
                assertThat(failureMessages(outbox.id()))
                        .anyMatch(message -> message.contains("forced snapshot failure"));
            });
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(inboxCount(outbox.id())).isEqualTo(1L);
            assertThat(snapshotCount(entityId, "like")).isEqualTo(1L);
            assertThat(bitCount("like", entityId, userId)).isEqualTo(1L);
            assertThat(counterService.getCounts("post", entityId, List.of("like")))
                    .containsEntry("like", 1L);
            assertThat(redis.opsForValue().get(
                    CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                    .isEqualTo(CounterReactionProjectionStore.COMPLETE_VERSION);
        });
        assertThat(initialRetryFailureCount(outbox.id())).isEqualTo(1L);
        awaitKafkaConsumerCaughtUp("counter-reaction-outbox");
        awaitKafkaConsumerCaughtUp("counter-reaction-outbox-retry");
    }

    @Test
    void snapshotFailureRollsBackInboxSoTheSameBatchCanBeRetried() {
        String entityId = "7002";
        jdbc.update(
                "INSERT INTO counter_snapshot "
                        + "(entity_type, entity_id, metric, count_value, updated_at) "
                        + "VALUES ('post', ?, 'view', ?, NOW(3))",
                entityId,
                Long.MAX_VALUE);
        CounterEvent event = CounterEvent.of("evt-overflow", "post", entityId, "view", 0, 0L, 1);

        assertThatThrownBy(() -> aggregationProcessor.applyBatch(List.of(event)))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_event_inbox WHERE event_id = 'evt-overflow'",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count_value FROM counter_snapshot "
                        + "WHERE entity_type = 'post' AND entity_id = ? AND metric = 'view'",
                Long.class,
                entityId)).isEqualTo(Long.MAX_VALUE);
        assertThat(redis.hasKey("counter:event:evt-overflow")).isFalse();

        jdbc.update(
                "UPDATE counter_snapshot SET count_value = 0 WHERE entity_type = 'post' "
                        + "AND entity_id = ? AND metric = 'view'",
                entityId);

        assertThat(aggregationProcessor.applyBatch(List.of(event))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_event_inbox WHERE event_id = 'evt-overflow'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count_value FROM counter_snapshot "
                        + "WHERE entity_type = 'post' AND entity_id = ? AND metric = 'view'",
                Long.class,
                entityId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_reaction WHERE entity_id = ?",
                Long.class,
                entityId)).isZero();
    }

    @Test
    void staleReactionEpochIsRecordedButCannotChangeTheCurrentSnapshot() {
        String entityId = "7003";
        jdbc.update(
                "INSERT INTO counter_snapshot "
                        + "(entity_type, entity_id, metric, count_value, fact_epoch, updated_at) "
                        + "VALUES ('post', ?, 'like', 1, 3, NOW(3)), "
                        + "('post', ?, 'fav', 0, 3, NOW(3))",
                entityId,
                entityId);
        CounterEvent stale = CounterEvent.of("evt-stale", "post", entityId, "like", 1, 42L, 1);
        stale.setFactEpoch(2L);

        assertThat(aggregationProcessor.applyBatch(List.of(stale))).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_event_inbox WHERE event_id = 'evt-stale'",
                Long.class)).isEqualTo(1L);
        Map<String, Object> snapshot = jdbc.queryForMap(
                "SELECT count_value, fact_epoch FROM counter_snapshot "
                        + "WHERE entity_type = 'post' AND entity_id = ? AND metric = 'like'",
                entityId);
        assertThat(((Number) snapshot.get("count_value")).longValue()).isEqualTo(1L);
        assertThat(((Number) snapshot.get("fact_epoch")).longValue()).isEqualTo(3L);
    }

    @Test
    void binarySnapshotIdentityKeepsCaseDistinctEntitiesSeparate() {
        CounterEvent upper = CounterEvent.of("evt-upper", "post", "Case", "like", 1, 42L, 1);
        CounterEvent lower = CounterEvent.of("evt-lower", "post", "case", "like", 1, 43L, 1);

        assertThat(aggregationProcessor.applyBatch(List.of(upper, lower))).isEqualTo(2);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_snapshot WHERE entity_type = 'post' "
                        + "AND entity_id IN ('Case', 'case') AND metric = 'like'",
                Long.class)).isEqualTo(2L);
    }

    @Test
    void reusedEventIdWithDifferentMutationIsRejected() {
        CounterEvent first = CounterEvent.of("evt-collision", "post", "7004", "like", 1, 42L, 1);
        CounterEvent collision = CounterEvent.of("evt-collision", "post", "7005", "like", 1, 43L, 1);
        assertThat(aggregationProcessor.applyBatch(List.of(first))).isEqualTo(1);

        assertThatThrownBy(() -> aggregationProcessor.applyBatch(List.of(collision)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collision");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_snapshot WHERE entity_id = '7005'",
                Long.class)).isZero();
    }

    @Test
    void malformedKafkaRecordIsPublishedToDltBeforeItsOffsetIsRecovered() throws Exception {
        AtomicReference<String> dltPayload = new AtomicReference<>();
        try (KafkaConsumer<String, String> probe = newProbeConsumer()) {
            probe.subscribe(List.of(CounterTopics.EVENTS + "-dlq"));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> {
                probe.poll(Duration.ofMillis(100));
                return !probe.assignment().isEmpty();
            });

            kafka.send(CounterTopics.EVENTS, "post:invalid:like", "not-json")
                    .get(10, TimeUnit.SECONDS);
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> {
                probe.poll(Duration.ofMillis(250)).forEach(record ->
                        dltPayload.compareAndSet(null, record.value()));
                return dltPayload.get() != null;
            });
        }

        assertThat(dltPayload.get()).isEqualTo("not-json");
        awaitAggregationConsumerCaughtUp();
    }

    @Test
    void kafkaListenerRetriesTheWholeBatchAfterOneTransientProcessorFailure() throws Exception {
        MessageListenerContainer container =
                kafkaListenerRegistry.getListenerContainer("counter-aggregation-events");
        assertThat(container).isNotNull();
        try {
            CountDownLatch stopped = new CountDownLatch(1);
            container.stop(stopped::countDown);
            assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();

            CounterEvent first = CounterEvent.of("evt-batch-retry-a", "post", "7008", "view", 0, 0L, 1);
            CounterEvent second = CounterEvent.of("evt-batch-retry-b", "post", "7008", "view", 0, 0L, 1);
            String firstPayload = objectMapper.writeValueAsString(first);
            String secondPayload = objectMapper.writeValueAsString(second);
            kafka.send(CounterTopics.EVENTS, "post:7008:view", firstPayload).get(10, TimeUnit.SECONDS);
            kafka.send(CounterTopics.EVENTS, "post:7008:view", secondPayload).get(10, TimeUnit.SECONDS);

            AtomicInteger attempts = new AtomicInteger();
            doAnswer(invocation -> {
                List<CounterEvent> batch = invocation.getArgument(0);
                boolean isTargetBatch = batch.stream().map(CounterEvent::getEventId).toList()
                        .containsAll(List.of("evt-batch-retry-a", "evt-batch-retry-b"));
                if (isTargetBatch && attempts.incrementAndGet() == 1) {
                    throw new TransientDataAccessResourceException("temporary MySQL failure");
                }
                return invocation.callRealMethod();
            }).when(aggregationProcessor).applyBatch(anyList());

            container.start();
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(attempts.get()).isEqualTo(2);
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM counter_event_inbox WHERE event_id IN (?, ?)",
                        Long.class,
                        "evt-batch-retry-a",
                        "evt-batch-retry-b")).isEqualTo(2L);
                assertThat(jdbc.queryForObject(
                        "SELECT count_value FROM counter_snapshot "
                                + "WHERE entity_type = 'post' AND entity_id = '7008' AND metric = 'view'",
                        Long.class)).isEqualTo(2L);
            });
            awaitAggregationConsumerCaughtUp();
        } finally {
            if (!container.isRunning()) {
                container.start();
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(container::isRunning);
            }
        }
    }

    @Test
    void scheduledCalibrationUsesMysqlFactsAndDiscardsRedisOnlyReactionState() {
        String mysqlBackedId = "7010";
        long firstUserId = 42L;
        long secondUserId = BitmapShard.CHUNK_SIZE + 43L;
        jdbc.update("""
                INSERT INTO counter_reaction
                    (entity_type, entity_id, metric, user_id, created_at)
                VALUES
                    ('post', ?, 'like', ?, NOW(3)),
                    ('post', ?, 'like', ?, NOW(3)),
                    ('post', ?, 'fav', ?, NOW(3))
                """, mysqlBackedId, firstUserId, mysqlBackedId, secondUserId,
                mysqlBackedId, secondUserId);
        jdbc.update("""
                INSERT INTO counter_snapshot
                    (entity_type, entity_id, metric, count_value, fact_epoch, updated_at)
                VALUES
                    ('post', ?, 'like', 99, 4, NOW(3)),
                    ('post', ?, 'fav', 99, 4, NOW(3))
                """, mysqlBackedId, mysqlBackedId);

        String redisOnlyId = "7011";
        long staleUserId = 44L;
        redis.opsForValue().setBit(
                CounterKeys.bitmapKey(
                        "like", "post", redisOnlyId, BitmapShard.chunkOf(staleUserId)),
                BitmapShard.bitOf(staleUserId),
                true);
        jdbc.update("""
                INSERT INTO counter_snapshot
                    (entity_type, entity_id, metric, count_value, fact_epoch, updated_at)
                VALUES
                    ('post', ?, 'like', 1, 2, NOW(3)),
                    ('post', ?, 'fav', 0, 2, NOW(3))
                """, redisOnlyId, redisOnlyId);

        calibrationService.reconcileScheduled();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_reaction WHERE entity_type = 'post' AND entity_id = ?",
                Long.class,
                mysqlBackedId)).isEqualTo(3L);
        assertThat(counterService.getCounts("post", mysqlBackedId, List.of("like", "fav")))
                .containsEntry("like", 2L)
                .containsEntry("fav", 1L);
        assertThat(readSds(mysqlBackedId))
                .containsExactly(0L, 2L, 1L, 0L, 0L);
        assertThat(counterService.isLiked("post", mysqlBackedId, firstUserId)).isTrue();
        assertThat(counterService.isLiked("post", mysqlBackedId, secondUserId)).isTrue();
        assertThat(counterService.isFaved("post", mysqlBackedId, secondUserId)).isTrue();
        assertThat(bitCount("like", mysqlBackedId, firstUserId)).isEqualTo(1L);
        assertThat(bitCount("like", mysqlBackedId, secondUserId)).isEqualTo(1L);
        assertThat(bitCount("fav", mysqlBackedId, secondUserId)).isEqualTo(1L);
        assertThat(snapshotCount(mysqlBackedId, "like")).isEqualTo(2L);
        assertThat(snapshotCount(mysqlBackedId, "fav")).isEqualTo(1L);
        assertThat(counterService.getCounts("post", redisOnlyId, List.of("like", "fav")))
                .containsEntry("like", 0L)
                .containsEntry("fav", 0L);
        assertThat(readSds(redisOnlyId))
                .containsExactly(0L, 0L, 0L, 0L, 0L);
        assertThat(bitCount("like", redisOnlyId, staleUserId)).isZero();
        assertThat(snapshotCount(redisOnlyId, "like")).isZero();
        assertThat(snapshotCount(redisOnlyId, "fav")).isZero();
        assertThat(redis.opsForValue().get(
                CounterKeys.reactionProjectionCompleteKey("post", mysqlBackedId)))
                .isEqualTo(CounterReactionProjectionStore.COMPLETE_VERSION);
        assertThat(redis.opsForValue().get(
                CounterKeys.reactionProjectionCompleteKey("post", redisOnlyId)))
                .isEqualTo(CounterReactionProjectionStore.COMPLETE_VERSION);
    }

    @Test
    void calibrationRepairsCommittedMysqlFactWhenOutboxProjectionHasNotRun() {
        String entityId = "7012";
        long userId = 43L;

        assertThat(counterService.like("post", entityId, userId)).isTrue();
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
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_event_inbox WHERE entity_id = ?",
                Long.class,
                entityId)).isZero();
        cleanRedis();

        CounterCalibrationService.ReconciliationResult result =
                calibrationService.reconcileEntity("post", entityId);

        assertThat(result).isEqualTo(
                new CounterCalibrationService.ReconciliationResult(1L, 0L, 1L));
        assertThat(bitCount("like", entityId, userId)).isEqualTo(1L);
        assertThat(counterService.getCounts("post", entityId, List.of("like", "fav")))
                .containsEntry("like", 1L)
                .containsEntry("fav", 0L);
        assertThat(jdbc.queryForObject(
                "SELECT count_value FROM counter_snapshot "
                        + "WHERE entity_type = 'post' AND entity_id = ? AND metric = 'like'",
                Long.class,
                entityId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count_value FROM counter_snapshot "
                        + "WHERE entity_type = 'post' AND entity_id = ? AND metric = 'fav'",
                Long.class,
                entityId)).isZero();
        assertThat(redis.opsForValue().get(
                CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                .isEqualTo(CounterReactionProjectionStore.COMPLETE_VERSION);
    }

    private KafkaConsumer<String, String> newProbeConsumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "counter-it-probe-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(properties);
    }

    private void awaitAggregationConsumerCaughtUp() {
        awaitKafkaConsumerCaughtUp(AGGREGATION_GROUP);
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

    private long initialRetryFailureCount(long outboxId) {
        return jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM dead_letter_messages
                        WHERE source_topic = ? AND message_key = ?
                          AND retry_count = 0 AND status = 'RETRYING'
                        """,
                Long.class,
                OutboxTopics.CANAL_OUTBOX,
                Long.toString(outboxId));
    }

    private List<String> failureMessages(long outboxId) {
        return jdbc.queryForList("""
                        SELECT exception_message
                        FROM dead_letter_messages
                        WHERE source_topic = ? AND message_key = ?
                        ORDER BY created_at
                        """,
                String.class,
                OutboxTopics.CANAL_OUTBOX,
                Long.toString(outboxId));
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

    private long bitCount(String metric, String entityId, long userId) {
        String bitmapKey = CounterKeys.bitmapKey(
                metric, "post", entityId, BitmapShard.chunkOf(userId));
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
