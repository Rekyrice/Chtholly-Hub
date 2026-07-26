package com.chtholly.common.kafka.deadletter;

import com.chtholly.common.kafka.DeadLetterStatus;
import com.chtholly.post.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterMessageServiceTest {

    @Mock
    private DeadLetterMessageMapper mapper;
    @Mock
    private SnowflakeIdGenerator idGenerator;

    private DeadLetterMessageService service;

    @BeforeEach
    void setUp() {
        service = new DeadLetterMessageService(mapper, idGenerator);
    }

    @Test
    void claimStoresAttemptTokenAndDatabaseDeliveryDeadline() {
        when(mapper.claimReplay(41L, "attempt-41", 300_000L))
                .thenReturn(1);

        assertThat(service.claimReplay(41L, "attempt-41", 300_000L))
                .isTrue();

        verify(mapper).claimReplay(41L, "attempt-41", 300_000L);
    }

    @Test
    void completionMatchesBothReplayingStateAndAttemptToken() {
        when(mapper.finishReplay(
                41L, "attempt-41", DeadLetterStatus.PENDING.name()))
                .thenReturn(1);

        assertThat(service.finishReplay(
                41L, "attempt-41", DeadLetterStatus.PENDING))
                .isTrue();

        verify(mapper).finishReplay(
                41L, "attempt-41", DeadLetterStatus.PENDING.name());
    }

    @Test
    void onlyExpiredClaimsRecoverAndTheyRemainUncertain() {
        when(mapper.recoverExpiredReplay(41L, "attempt-41"))
                .thenReturn(1);

        assertThat(service.recoverExpiredReplay(
                41L, "attempt-41")).isTrue();

        verify(mapper).recoverExpiredReplay(41L, "attempt-41");
    }

    @Test
    void rejectsUnsafeTargetsAndInvalidClaimMetadata() {
        assertThatThrownBy(() ->
                service.finishReplay(
                        41L, "attempt-41", DeadLetterStatus.REPLAYING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                service.resolveUncertain(
                        41L, "attempt-41", DeadLetterStatus.UNCERTAIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                service.recoverExpiredReplay(41L, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                service.resolveUncertain(
                        41L, " ", DeadLetterStatus.DEAD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                service.claimReplay(41L, " ", 300_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                service.claimReplay(41L, "attempt-41", 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapperContractsFenceClaimCompletionRecoveryAndResolution()
            throws Exception {
        String source = new ClassPathResource(
                "mapper/DeadLetterMessageMapper.xml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(source)
                .contains("<update id=\"claimReplay\">")
                .contains("status = 'REPLAYING'")
                .contains("replay_attempt_token = #{attemptToken}")
                .contains("AND status = 'DEAD'")
                .contains("<update id=\"finishReplay\">")
                .contains("AND replay_attempt_token = #{attemptToken}")
                .contains("<update id=\"recoverExpiredReplay\">")
                .contains("AND replay_attempt_token = #{attemptToken}")
                .contains("replay_deadline_at &lt;= CURRENT_TIMESTAMP(3)")
                .contains("status = 'UNCERTAIN'")
                .contains("<update id=\"resolveUncertain\">")
                .contains("AND status = 'UNCERTAIN'");
    }
}
