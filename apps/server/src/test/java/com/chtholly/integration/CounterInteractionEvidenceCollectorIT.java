package com.chtholly.integration;

import com.chtholly.counter.schema.BitmapShard;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.service.CounterReactionCommandService;
import com.chtholly.counter.service.CounterService;
import com.chtholly.counter.service.impl.CounterCalibrationService;
import com.chtholly.relation.outbox.OutboxTopics;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Collects one fixed MySQL/Outbox/Kafka/Redis interaction correctness run. */
@EnabledIfSystemProperty(named = "counter.evidence.enabled", matches = "true")
@TestPropertySource(properties = "counter.calibration.enabled=false")
@Import(CounterEvidenceSqlProbe.Configuration.class)
class CounterInteractionEvidenceCollectorIT extends AbstractGoldenPathIT {

    private static final String ENTITY_ID = "7202607190001";
    private static final String REACTION_CONSUMER_GROUP = "counter-reaction-outbox";

    @Autowired
    private CounterService counterService;
    @Autowired
    private CounterCalibrationService calibrationService;
    @Autowired
    private KafkaTemplate<String, String> kafka;
    @Autowired
    private CounterEvidenceSqlProbe sqlProbe;

    @BeforeEach
    void resetState() {
        cleanRedis();
        cleanDatabase();
        assertThat(calibrationService.reconcileEntity("post", ENTITY_ID))
                .isEqualTo(new CounterCalibrationService.ReconciliationResult(0L, 0L, 1L));
        sqlProbe.reset();
    }

    @Test
    void collectsOutboxReplayDelayedDeliveryAndMysqlRecoveryEvidence() throws Exception {
        Instant startedAt = Instant.now();
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
        assertThat(changes)
                .containsExactly(true, false, true, false, true, false, true, false);

        List<OutboxRow> outboxRows = reactionOutboxRows();
        assertThat(outboxRows).hasSize(4);
        assertThat(outboxRows).allSatisfy(row -> {
            assertThat(row.aggregateType())
                    .isEqualTo(CounterReactionCommandService.OUTBOX_AGGREGATE_TYPE);
            assertThat(row.eventType())
                    .isEqualTo(CounterReactionCommandService.OUTBOX_EVENT_TYPE);
        });

        for (int index = 0; index < 3; index++) {
            send(outboxRows.get(index));
        }
        send(outboxRows.getFirst());
        awaitKafkaConsumerCaughtUp(REACTION_CONSUMER_GROUP);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(inboxCount()).isEqualTo(3L));

        assertThat(authoritativeCount("like")).isEqualTo(2L);
        assertThat(snapshotCount("like")).isEqualTo(1L);
        assertThat(bitCount("like")).isEqualTo(2L);
        assertThat(counterService.getCounts("post", ENTITY_ID, List.of("like")))
                .containsEntry("like", 2L);
        long preCalibrationDiscrepancy = discrepancy();

        CounterCalibrationService.ReconciliationResult reconciliation =
                calibrationService.reconcileEntity("post", ENTITY_ID);
        assertThat(reconciliation)
                .isEqualTo(new CounterCalibrationService.ReconciliationResult(2L, 0L, 2L));

        // Deliver the fourth durable event after the absolute rebuild. Its old epoch is recorded
        // in the Inbox but cannot perturb the rebuilt snapshot.
        send(outboxRows.get(3));
        awaitKafkaConsumerCaughtUp(REACTION_CONSUMER_GROUP);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(inboxCount()).isEqualTo(4L));
        long postCalibrationDiscrepancy = discrepancy();

        int requestTotal = changes.length;
        long stateChangeCount = 0L;
        for (boolean change : changes) {
            if (change) {
                stateChangeCount++;
            }
        }
        int outboxRowCount = outboxRows.size();
        int kafkaDeliveryCount = 5;
        int inboxDedupHitCount = kafkaDeliveryCount - Math.toIntExact(inboxCount());
        int snapshotWriteCount = sqlProbe.count();
        CounterEvidenceResultWriter.Metrics metrics =
                new CounterEvidenceResultWriter.Metrics(
                        requestTotal,
                        stateChangeCount,
                        outboxRowCount,
                        kafkaDeliveryCount,
                        inboxDedupHitCount,
                        snapshotWriteCount,
                        preCalibrationDiscrepancy,
                        postCalibrationDiscrepancy);
        assertThat(metrics).isEqualTo(new CounterEvidenceResultWriter.Metrics(
                8, 4, 4, 5, 1, 4, 1, 0));

        Map<String, Long> redisCounts =
                counterService.getCounts("post", ENTITY_ID, List.of("like", "fav"));
        CounterEvidenceResultWriter.CalibratedCounts calibratedCounts =
                new CounterEvidenceResultWriter.CalibratedCounts(
                        bitCount("like"),
                        redisCounts.get("like"),
                        snapshotCount("like"),
                        authoritativeCount("like"),
                        bitCount("fav"),
                        redisCounts.get("fav"),
                        snapshotCount("fav"),
                        authoritativeCount("fav"),
                        reconciliation.factEpoch());
        assertThat(calibratedCounts).isEqualTo(
                new CounterEvidenceResultWriter.CalibratedCounts(
                        2L, 2L, 2L, 2L, 0L, 0L, 0L, 0L, 2L));
        new CounterEvidenceResultWriter(objectMapper).write(
                startedAt,
                metrics,
                calibratedCounts,
                MYSQL.getDockerImageName(),
                REDIS.getDockerImageName(),
                KAFKA.getDockerImageName());
    }

    private List<OutboxRow> reactionOutboxRows() {
        return jdbc.query("""
                        SELECT id, aggregate_type, type, payload
                        FROM outbox
                        WHERE aggregate_type = ?
                        ORDER BY id
                        """,
                (resultSet, rowNumber) -> new OutboxRow(
                        resultSet.getLong("id"),
                        resultSet.getString("aggregate_type"),
                        resultSet.getString("type"),
                        resultSet.getString("payload")),
                CounterReactionCommandService.OUTBOX_AGGREGATE_TYPE);
    }

    private void send(OutboxRow row) throws Exception {
        kafka.send(
                        OutboxTopics.CANAL_OUTBOX,
                        Long.toString(row.id()),
                        canalEnvelope(
                                row.id(),
                                row.aggregateType(),
                                row.eventType(),
                                row.payload()))
                .get(10, TimeUnit.SECONDS);
    }

    private long discrepancy() {
        return spread(
                        bitCount("like"),
                        counterService.getCounts(
                                "post", ENTITY_ID, List.of("like")).get("like"),
                        snapshotCount("like"),
                        authoritativeCount("like"))
                + spread(
                        bitCount("fav"),
                        counterService.getCounts(
                                "post", ENTITY_ID, List.of("fav")).get("fav"),
                        snapshotCount("fav"),
                        authoritativeCount("fav"));
    }

    private long bitCount(String metric) {
        String key = CounterKeys.bitmapKey(
                metric, "post", ENTITY_ID, BitmapShard.chunkOf(41L));
        Long count = redis.execute((RedisCallback<Long>) connection ->
                connection.stringCommands().bitCount(
                        key.getBytes(StandardCharsets.UTF_8)));
        return count == null ? 0L : count;
    }

    private long snapshotCount(String metric) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(count_value), 0)
                FROM counter_snapshot
                WHERE entity_type = 'post' AND entity_id = ? AND metric = ?
                """, Long.class, ENTITY_ID, metric);
        return value == null ? 0L : value;
    }

    private long authoritativeCount(String metric) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM counter_reaction
                WHERE entity_type = 'post' AND entity_id = ? AND metric = ?
                """, Long.class, ENTITY_ID, metric);
        return value == null ? 0L : value;
    }

    private long inboxCount() {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM counter_event_inbox
                WHERE entity_type = 'post' AND entity_id = ?
                """, Long.class, ENTITY_ID);
        return value == null ? 0L : value;
    }

    private static long spread(long first, long second, long third, long fourth) {
        long maximum = Math.max(Math.max(first, second), Math.max(third, fourth));
        long minimum = Math.min(Math.min(first, second), Math.min(third, fourth));
        return maximum - minimum;
    }

    private record OutboxRow(
            long id,
            String aggregateType,
            String eventType,
            String payload) {}
}
