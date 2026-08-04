package com.chtholly.common.kafka.deadletter;

import com.chtholly.common.kafka.DeadLetterStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterControllerTest {

    private static final long ID = 41L;
    private static final String ATTEMPT = "attempt-41";

    @Mock
    private DeadLetterMessageService deadLetterMessageService;
    @Mock
    private DeadLetterReplayService deadLetterReplayService;

    private DeadLetterController controller;

    @BeforeEach
    void setUp() {
        controller = new DeadLetterController(
                deadLetterMessageService, deadLetterReplayService);
    }

    @Test
    void mapsFilteredDeadLetterPage() {
        when(deadLetterMessageService.listResults(
                "canal-outbox", "DEAD", 2, 10))
                .thenReturn(List.of(result(DeadLetterStatus.DEAD)));
        when(deadLetterMessageService.count("canal-outbox", "DEAD"))
                .thenReturn(21L);

        DeadLetterPageResponse response = controller.list(
                "canal-outbox", "DEAD", 2, 10);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().id()).isEqualTo(ID);
        assertThat(response.total()).isEqualTo(21L);
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(10);
    }

    @Test
    void mapsReplayResultFromApplicationService() {
        when(deadLetterReplayService.replay(ID))
                .thenReturn(result(DeadLetterStatus.PENDING));

        DeadLetterResponse response = controller.replay(ID);

        verify(deadLetterReplayService).replay(ID);
        assertThat(response.status()).isEqualTo(DeadLetterStatus.PENDING.name());
        assertThat(response.replayAttemptToken()).isEqualTo(ATTEMPT);
    }

    @Test
    void mapsExpiredRecoveryResultFromApplicationService() {
        when(deadLetterReplayService.recoverExpired(ID, ATTEMPT))
                .thenReturn(result(DeadLetterStatus.UNCERTAIN));

        DeadLetterResponse response = controller.recoverExpired(ID, ATTEMPT);

        verify(deadLetterReplayService).recoverExpired(ID, ATTEMPT);
        assertThat(response.status())
                .isEqualTo(DeadLetterStatus.UNCERTAIN.name());
    }

    @Test
    void mapsManualResolutionResultFromApplicationService() {
        when(deadLetterReplayService.resolve(ID, ATTEMPT, false))
                .thenReturn(result(DeadLetterStatus.DEAD));

        DeadLetterResponse response = controller.resolve(ID, ATTEMPT, false);

        verify(deadLetterReplayService).resolve(ID, ATTEMPT, false);
        assertThat(response.status()).isEqualTo(DeadLetterStatus.DEAD.name());
    }

    @Test
    void forwardsVerifiedPublicationResolution() {
        when(deadLetterReplayService.resolve(ID, ATTEMPT, true))
                .thenReturn(result(DeadLetterStatus.PENDING));

        DeadLetterResponse response = controller.resolve(ID, ATTEMPT, true);

        verify(deadLetterReplayService).resolve(ID, ATTEMPT, true);
        assertThat(response.status()).isEqualTo(DeadLetterStatus.PENDING.name());
        assertThat(response.replayAttemptToken()).isEqualTo(ATTEMPT);
    }

    private static DeadLetterReplayResult result(DeadLetterStatus status) {
        return DeadLetterReplayResult.from(row(status));
    }

    private static DeadLetterMessageRow row(DeadLetterStatus status) {
        DeadLetterMessageRow row = new DeadLetterMessageRow();
        row.setId(ID);
        row.setSourceTopic("canal-outbox");
        row.setMessageKey("reaction-41");
        row.setMessageValue("{\"id\":41}");
        row.setExceptionClass(IllegalStateException.class.getName());
        row.setExceptionMessage("failed");
        row.setRetryCount(3);
        row.setStatus(status.name());
        row.setReplayAttemptToken(ATTEMPT);
        return row;
    }
}
