package com.chtholly.integration;

import com.chtholly.counter.event.CounterAggregationProcessor;
import com.chtholly.counter.event.CounterEvent;
import com.chtholly.counter.event.CounterTopics;
import com.chtholly.counter.schema.BitmapShard;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.counter.service.CounterService;
import com.chtholly.counter.service.impl.CounterBitmapIndexService;
import com.chtholly.counter.service.impl.CounterCalibrationService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Collects one fixed real Redis/Kafka/MySQL counter correctness run. */
@EnabledIfSystemProperty(named = "counter.evidence.enabled", matches = "true")
@TestPropertySource(properties = "counter.calibration.enabled=false")
@Import(CounterEvidenceSqlProbe.Configuration.class)
class CounterInteractionEvidenceCollectorIT extends AbstractGoldenPathIT {

    private static final String ENTITY_ID = "7202607190001";
    private static final String PARTITION_KEY = "post:" + ENTITY_ID + ":like";

    @Autowired private CounterService counterService;
    @Autowired private CounterAggregationProcessor aggregationProcessor;
    @Autowired private CounterCalibrationService calibrationService;
    @Autowired private CounterBitmapIndexService bitmapIndex;
    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private CounterEvidenceSqlProbe sqlProbe;
    private MessageListenerContainer aggregationListener;

    @BeforeEach
    void resetState() throws InterruptedException {
        aggregationListener = listeners.getListenerContainer("counter-aggregation-events");
        assertThat(aggregationListener).isNotNull();
        stop(aggregationListener);
        cleanRedis();
        cleanDatabase();
        seedEmptyCounter();
        sqlProbe.reset();
    }

    @AfterEach
    void restartAggregationListener() {
        if (aggregationListener != null && !aggregationListener.isRunning()) {
            aggregationListener.start();
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(aggregationListener::isRunning);
        }
    }

    @Test
    void collectsFixedInteractionAndCalibrationEvidence() throws Exception {
        Instant startedAt = Instant.now();
        try (KafkaConsumer<String, String> probe = newProbeConsumer()) {
            startAtTopicEnd(probe);
            boolean[] changes = {
                    counterService.like("post", ENTITY_ID, 41L),
                    counterService.like("post", ENTITY_ID, 41L),
                    counterService.like("post", ENTITY_ID, 42L),
                    counterService.like("post", ENTITY_ID, 42L),
                    counterService.unlike("post", ENTITY_ID, 41L),
                    counterService.unlike("post", ENTITY_ID, 41L),
                    counterService.like("post", ENTITY_ID, 41L),
                    counterService.like("post", ENTITY_ID, 41L)
            };
            assertThat(changes).containsExactly(true, false, true, false, true, false, true, false);

            List<CapturedEvent> originals = pollEvents(probe, 4);
            kafka.send(CounterTopics.EVENTS, PARTITION_KEY, originals.getFirst().payload())
                    .get(10, TimeUnit.SECONDS);
            List<CapturedEvent> replays = pollEvents(probe, 1);
            assertThat(originals.stream().map(CapturedEvent::event).map(CounterEvent::getDelta))
                    .containsExactly(1, 1, -1, 1);
            assertThat(replays.getFirst().payload()).isEqualTo(originals.getFirst().payload());
            assertThat(replays.getFirst().event().getEventId())
                    .isEqualTo(originals.getFirst().event().getEventId());

            int firstApplied = aggregationProcessor.applyBatch(
                    originals.subList(0, 3).stream().map(CapturedEvent::event).toList());
            int replayApplied = aggregationProcessor.applyBatch(List.of(replays.getFirst().event()));
            assertThat(firstApplied).isEqualTo(3);
            assertThat(replayApplied).isZero();
            long preCalibrationDiscrepancy = discrepancy();
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> {
                bitmapIndex.discoverCandidates(0);
                return bitmapIndex.isBackfillComplete();
            });
            CounterCalibrationService.ReconciliationResult reconciliation =
                    calibrationService.reconcileEntity("post", ENTITY_ID);
            long postCalibrationDiscrepancy = discrepancy();

            int requestTotal = changes.length;
            long stateChangeCount = 0L;
            for (boolean change : changes) {
                if (change) { stateChangeCount++; }
            }
            int kafkaEventCount = originals.size() + replays.size();
            int dedupHitCount = 1 - replayApplied;
            int aggregationBatchCount = 2;
            int mysqlUpdateCount = sqlProbe.count();
            CounterEvidenceResultWriter.Metrics metrics = new CounterEvidenceResultWriter.Metrics(
                    requestTotal, stateChangeCount, kafkaEventCount, dedupHitCount,
                    aggregationBatchCount, mysqlUpdateCount, preCalibrationDiscrepancy,
                    postCalibrationDiscrepancy);
            assertThat(metrics).isEqualTo(new CounterEvidenceResultWriter.Metrics(8, 4, 5, 1, 2, 2, 1, 0));
            assertThat(reconciliation).isEqualTo(
                    new CounterCalibrationService.ReconciliationResult(2L, 0L, 1L));

            Map<String, Long> redisCounts = counterService.getCounts("post", ENTITY_ID, List.of("like", "fav"));
            CounterEvidenceResultWriter.CalibratedCounts calibratedCounts =
                    new CounterEvidenceResultWriter.CalibratedCounts(
                            bitCount("like"), redisCounts.get("like"), mysqlCount("like"),
                            bitCount("fav"), redisCounts.get("fav"), mysqlCount("fav"),
                            reconciliation.factEpoch());
            new CounterEvidenceResultWriter(objectMapper).write(
                    startedAt, metrics, calibratedCounts,
                    MYSQL.getDockerImageName(), REDIS.getDockerImageName(), KAFKA.getDockerImageName());
        }
    }

    private void seedEmptyCounter() {
        byte[] value = new byte[CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE];
        redis.execute((RedisCallback<Void>) connection -> {
            connection.stringCommands().set(CounterKeys.sdsKey("post", ENTITY_ID)
                    .getBytes(StandardCharsets.UTF_8), value);
            return null;
        });
    }

    private static void stop(MessageListenerContainer listener) throws InterruptedException {
        assertThat(listener.isRunning()).isTrue();
        CountDownLatch stopped = new CountDownLatch(1);
        listener.stop(stopped::countDown);
        assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private KafkaConsumer<String, String> newProbeConsumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "counter-evidence-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(properties);
    }

    private static void startAtTopicEnd(KafkaConsumer<String, String> probe) {
        probe.subscribe(List.of(CounterTopics.EVENTS));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> {
            probe.poll(Duration.ofMillis(100));
            return !probe.assignment().isEmpty();
        });
        probe.seekToEnd(probe.assignment());
        probe.assignment().forEach(probe::position);
    }

    private List<CapturedEvent> pollEvents(KafkaConsumer<String, String> probe, int expected) {
        List<CapturedEvent> events = new ArrayList<>();
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> {
            probe.poll(Duration.ofMillis(250)).forEach(record -> {
                if (PARTITION_KEY.equals(record.key())) {
                    try {
                        events.add(new CapturedEvent(record.value(),
                                objectMapper.readValue(record.value(), CounterEvent.class)));
                    } catch (Exception exception) {
                        throw new IllegalStateException("Counter evidence event is invalid", exception);
                    }
                }
            });
            return events.size() >= expected;
        });
        assertThat(events).hasSize(expected);
        return List.copyOf(events);
    }

    private long discrepancy() {
        Map<String, Long> redisCounts = counterService.getCounts("post", ENTITY_ID, List.of("like", "fav"));
        long bitmapLike = bitCount("like");
        long bitmapFav = bitCount("fav");
        long mysqlLike = mysqlCount("like");
        long mysqlFav = mysqlCount("fav");
        return spread(bitmapLike, redisCounts.get("like"), mysqlLike)
                + spread(bitmapFav, redisCounts.get("fav"), mysqlFav);
    }

    private long bitCount(String metric) {
        String key = CounterKeys.bitmapKey(metric, "post", ENTITY_ID, BitmapShard.chunkOf(41L));
        Long count = redis.execute((RedisCallback<Long>) connection ->
                connection.stringCommands().bitCount(key.getBytes(StandardCharsets.UTF_8)));
        return count == null ? 0L : count;
    }

    private long mysqlCount(String metric) {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(count_value), 0) FROM counter_snapshot "
                + "WHERE entity_type = 'post' AND entity_id = ? AND metric = ?", Long.class, ENTITY_ID, metric);
        return value == null ? 0L : value;
    }

    private static long spread(long first, long second, long third) {
        return Math.max(first, Math.max(second, third)) - Math.min(first, Math.min(second, third));
    }

    private record CapturedEvent(String payload, CounterEvent event) { }
}
