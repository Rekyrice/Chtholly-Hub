package com.chtholly.integration;

import com.chtholly.relation.service.RelationService;
import com.chtholly.relation.outbox.OutboxTopics;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden-path contracts for Following, Outbox and follower projection updates.
 */
class RelationGoldenPathIT extends AbstractGoldenPathIT {

    private static final long FROM_USER_ID = 201L;
    private static final long TO_USER_ID = 202L;

    @Autowired
    private RelationService relationService;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @BeforeEach
    void setUpData() {
        cleanRedis();
        cleanDatabase();
        jdbc.update("INSERT INTO users (id, nickname, handle) VALUES (?, ?, ?)",
                FROM_USER_ID, "Follower", "integration-follower");
        jdbc.update("INSERT INTO users (id, nickname, handle) VALUES (?, ?, ?)",
                TO_USER_ID, "Target", "integration-target");
    }

    @Test
    void rollsBackFollowingWhenOutboxInsertFails() {
        jdbc.execute("""
                CREATE TRIGGER it_fail_outbox BEFORE INSERT ON outbox
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced outbox failure'
                """);
        try {
            assertThatThrownBy(() -> relationService.follow(FROM_USER_ID, TO_USER_ID))
                    .hasMessageContaining("forced outbox failure");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS it_fail_outbox");
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM following
                WHERE from_user_id = ? AND to_user_id = ?
                """, Long.class, FROM_USER_ID, TO_USER_ID)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class)).isZero();
    }

    @Test
    void replayedCanalEquivalentEventsConvergeFollowerProjectionCacheAndCounters() throws Exception {
        assertThat(relationService.follow(FROM_USER_ID, TO_USER_ID)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM following
                WHERE from_user_id = ? AND to_user_id = ? AND rel_status = 1
                """, Long.class, FROM_USER_ID, TO_USER_ID)).isOne();

        Map<String, Object> created = outboxEvent("FollowCreated");
        publishTwice(created);
        long createdEventId = ((Number) created.get("id")).longValue();

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM follower
                    WHERE from_user_id = ? AND to_user_id = ? AND rel_status = 1
                    """, Long.class, FROM_USER_ID, TO_USER_ID)).isOne();
            assertThat(redis.opsForZSet().score(
                    "uf:flws:" + FROM_USER_ID, String.valueOf(TO_USER_ID))).isNotNull();
            assertThat(redis.opsForZSet().score(
                    "uf:fans:" + TO_USER_ID, String.valueOf(FROM_USER_ID))).isNotNull();
            assertThat(userCounter(FROM_USER_ID, 0)).isEqualTo(1L);
            assertThat(userCounter(TO_USER_ID, 1)).isEqualTo(1L);
            assertThat(redis.hasKey("consumed:outbox:relation:" + createdEventId)).isTrue();
        });

        assertThat(relationService.unfollow(FROM_USER_ID, TO_USER_ID)).isTrue();
        Map<String, Object> canceled = outboxEvent("FollowCanceled");
        publishTwice(canceled);
        long canceledEventId = ((Number) canceled.get("id")).longValue();

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM follower
                    WHERE from_user_id = ? AND to_user_id = ? AND rel_status = 1
                    """, Long.class, FROM_USER_ID, TO_USER_ID)).isZero();
            assertThat(redis.opsForZSet().score(
                    "uf:flws:" + FROM_USER_ID, String.valueOf(TO_USER_ID))).isNull();
            assertThat(redis.opsForZSet().score(
                    "uf:fans:" + TO_USER_ID, String.valueOf(FROM_USER_ID))).isNull();
            assertThat(userCounter(FROM_USER_ID, 0)).isZero();
            assertThat(userCounter(TO_USER_ID, 1)).isZero();
            assertThat(redis.hasKey("consumed:outbox:relation:" + canceledEventId)).isTrue();
        });
    }

    private Map<String, Object> outboxEvent(String type) {
        return jdbc.queryForMap("""
                SELECT id, payload FROM outbox
                WHERE aggregate_type = 'following' AND type = ?
                ORDER BY created_at DESC LIMIT 1
                """, type);
    }

    private void publishTwice(Map<String, Object> outbox) throws Exception {
        long eventId = ((Number) outbox.get("id")).longValue();
        String envelope = canalEnvelope(eventId, String.valueOf(outbox.get("payload")));
        kafka.send(OutboxTopics.CANAL_OUTBOX, envelope).get(10, TimeUnit.SECONDS);
        kafka.send(OutboxTopics.CANAL_OUTBOX, envelope).get(10, TimeUnit.SECONDS);
    }

    private long userCounter(long userId, int fieldIndex) {
        byte[] raw = redis.execute((RedisCallback<byte[]>) connection -> connection.stringCommands().get(
                ("ucnt:" + userId).getBytes(StandardCharsets.UTF_8)));
        if (raw == null || raw.length < (fieldIndex + 1) * 4) {
            return 0L;
        }
        long value = 0L;
        int offset = fieldIndex * 4;
        for (int i = 0; i < 4; i++) {
            value = (value << 8) | (raw[offset + i] & 0xffL);
        }
        return value;
    }
}
