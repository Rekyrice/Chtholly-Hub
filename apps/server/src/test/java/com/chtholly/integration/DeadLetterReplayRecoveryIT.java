package com.chtholly.integration;

import com.chtholly.common.kafka.DeadLetterStatus;
import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies tokenized manual replay claims and stale recovery against MySQL. */
@TestPropertySource(properties = {
        "counter.calibration.enabled=false"
})
class DeadLetterReplayRecoveryIT extends AbstractGoldenPathIT {

    @Autowired
    private DeadLetterMessageService deadLetterMessageService;

    @BeforeEach
    void resetState() {
        cleanDatabase();
    }

    @Test
    void claimCompletionAndRecoveryAreTokenFencedAcrossAttempts() {
        insertDeadRow(41L);

        assertThat(deadLetterMessageService.claimReplay(
                41L, "attempt-one", 300_000L)).isTrue();
        assertThat(deadLetterMessageService.claimReplay(
                41L, "attempt-two", 300_000L)).isFalse();
        assertThat(deadLetterMessageService.finishReplay(
                41L, "attempt-two", DeadLetterStatus.PENDING)).isFalse();
        assertThat(deadLetterMessageService.recoverExpiredReplay(
                41L, "attempt-one"))
                .isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT replay_attempt_token
                FROM dead_letter_messages
                WHERE id = 41
                """, String.class)).isEqualTo("attempt-one");

        jdbc.update("""
                UPDATE dead_letter_messages
                SET replay_deadline_at =
                    TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(3))
                WHERE id = 41
                """);
        assertThat(deadLetterMessageService.recoverExpiredReplay(
                41L, "attempt-two"))
                .isFalse();
        assertThat(deadLetterMessageService.recoverExpiredReplay(
                41L, "attempt-one"))
                .isTrue();
        assertThat(deadLetterMessageService.finishReplay(
                41L, "attempt-one", DeadLetterStatus.PENDING)).isFalse();
        assertThat(deadLetterMessageService.resolveUncertain(
                41L, "attempt-two", DeadLetterStatus.DEAD)).isFalse();
        assertThat(deadLetterMessageService.resolveUncertain(
                41L, "attempt-one", DeadLetterStatus.DEAD)).isTrue();

        assertThat(deadLetterMessageService.claimReplay(
                41L, "attempt-two", 300_000L)).isTrue();
        jdbc.update("""
                UPDATE dead_letter_messages
                SET replay_deadline_at =
                    TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(3))
                WHERE id = 41
                """);
        assertThat(deadLetterMessageService.recoverExpiredReplay(
                41L, "attempt-one")).isFalse();
        assertThat(deadLetterMessageService.recoverExpiredReplay(
                41L, "attempt-two")).isTrue();
        assertThat(deadLetterMessageService.resolveUncertain(
                41L, "attempt-one", DeadLetterStatus.DEAD)).isFalse();
        assertThat(deadLetterMessageService.resolveUncertain(
                41L, "attempt-two", DeadLetterStatus.DEAD)).isTrue();

        assertThat(deadLetterMessageService.claimReplay(
                41L, "attempt-three", 300_000L)).isTrue();
        assertThat(deadLetterMessageService.finishReplay(
                41L, "attempt-one", DeadLetterStatus.PENDING)).isFalse();
        assertThat(deadLetterMessageService.finishReplay(
                41L, "attempt-two", DeadLetterStatus.PENDING)).isFalse();
        assertThat(deadLetterMessageService.finishReplay(
                41L, "attempt-three", DeadLetterStatus.PENDING)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT status
                FROM dead_letter_messages
                WHERE id = 41
                """, String.class)).isEqualTo(DeadLetterStatus.PENDING.name());
    }

    private void insertDeadRow(long id) {
        jdbc.update("""
                INSERT INTO dead_letter_messages (
                    id, source_topic, message_key, message_value,
                    retry_count, status
                ) VALUES (?, 'canal-outbox', 'reaction-41', '{}', 3, 'DEAD')
                """, id);
    }
}
