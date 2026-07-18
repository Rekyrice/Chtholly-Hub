package com.chtholly.integration;

import com.chtholly.counter.event.CounterAggregationProcessor;
import com.chtholly.counter.event.CounterEvent;
import com.chtholly.counter.event.CounterTopics;
import com.chtholly.counter.schema.BitmapShard;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.service.CounterService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the counter path against real Redis, Kafka, and MySQL instances. */
class CounterGoldenPathIT extends AbstractGoldenPathIT {

    private static final String AGGREGATION_GROUP = "counter-agg";

    @Autowired
    private CounterService counterService;

    @Autowired
    private CounterAggregationProcessor aggregationProcessor;

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
    void duplicateKafkaDeliveryConvergesToOneLikeAndOneBitmapBit() throws Exception {
        String entityId = "7001";
        long userId = 42L;
        AtomicReference<String> capturedPayload = new AtomicReference<>();

        try (KafkaConsumer<String, String> probe = newProbeConsumer()) {
            probe.subscribe(List.of(CounterTopics.EVENTS));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> {
                probe.poll(Duration.ofMillis(100));
                return !probe.assignment().isEmpty();
            });

            assertThat(counterService.like("post", entityId, userId)).isTrue();
            Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> {
                probe.poll(Duration.ofMillis(250)).forEach(record -> capturedPayload.compareAndSet(null, record.value()));
                return capturedPayload.get() != null;
            });
        }

        CounterEvent emitted = objectMapper.readValue(capturedPayload.get(), CounterEvent.class);
        assertThat(emitted.getEventId()).isNotBlank();

        awaitAggregationConsumerCaughtUp();
        restartAggregationConsumer();

        // Replay the exact business event with the same partition key and event ID.
        String partitionKey = "post:" + entityId + ":like";
        kafka.send(CounterTopics.EVENTS, partitionKey, capturedPayload.get()).get(10, TimeUnit.SECONDS);
        awaitAggregationConsumerCaughtUp();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(counterService.getCounts("post", entityId, List.of("like")))
                        .containsEntry("like", 1L));

        String bitmapKey = CounterKeys.bitmapKey(
                "like", "post", entityId, BitmapShard.chunkOf(userId));
        Long bitCount = redis.execute((RedisCallback<Long>) connection ->
                connection.stringCommands().bitCount(bitmapKey.getBytes(StandardCharsets.UTF_8)));

        assertThat(bitCount).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_event_inbox WHERE event_id = ?",
                Long.class,
                emitted.getEventId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count_value FROM counter_snapshot "
                        + "WHERE entity_type = 'post' AND entity_id = ? AND metric = 'like'",
                Long.class,
                entityId)).isEqualTo(1L);
        assertThat(redis.hasKey("counter:event:" + emitted.getEventId())).isFalse();
        assertThat(redis.opsForHash().entries(CounterKeys.aggKey("post", entityId))).isEmpty();
    }

    @Test
    void snapshotFailureRollsBackInboxSoTheEventCanBeRetried() {
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
    }

    @Test
    void staleReactionEpochIsRecordedButCannotChangeTheCurrentSnapshot() {
        String entityId = "7003";
        jdbc.update(
                "INSERT INTO counter_snapshot "
                        + "(entity_type, entity_id, metric, count_value, fact_epoch, updated_at) "
                        + "VALUES ('post', ?, 'like', 1, 3, NOW(3))",
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

    private KafkaConsumer<String, String> newProbeConsumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "counter-it-probe-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(properties);
    }

    private void restartAggregationConsumer() throws InterruptedException {
        MessageListenerContainer container = kafkaListenerRegistry.getListenerContainer("counter-aggregation-events");
        assertThat(container).isNotNull();
        CountDownLatch stopped = new CountDownLatch(1);
        container.stop(stopped::countDown);
        assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
        container.start();
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(container::isRunning);
    }

    private void awaitAggregationConsumerCaughtUp() {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(properties)) {
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> {
                Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed = admin
                        .listConsumerGroupOffsets(AGGREGATION_GROUP)
                        .partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS);
                Map<TopicPartition, org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo> ends = admin
                        .listOffsets(committed.keySet().stream().collect(java.util.stream.Collectors.toMap(
                                partition -> partition,
                                partition -> org.apache.kafka.clients.admin.OffsetSpec.latest())))
                        .all()
                        .get(5, TimeUnit.SECONDS);
                return !committed.isEmpty() && committed.entrySet().stream()
                        .allMatch(entry -> entry.getValue().offset() >= ends.get(entry.getKey()).offset());
            });
        }
    }
}
