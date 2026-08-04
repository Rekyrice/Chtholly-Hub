package com.chtholly.common.kafka.deadletter;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.kafka.DeadLetterStatus;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterReplayServiceTest {

    private static final long ID = 41L;
    private static final String ATTEMPT = "attempt-41";
    private static final long RECOVERY_HORIZON_MILLIS = 300_000L;

    @Mock
    private DeadLetterMessageService deadLetterMessageService;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private SendResult<String, String> sendResult;
    @Mock
    private ProducerFactory<String, String> producerFactory;

    private DeadLetterReplayService service;

    @BeforeEach
    void setUp() {
        service = new DeadLetterReplayService(
                deadLetterMessageService,
                kafkaTemplate,
                10L,
                RECOVERY_HORIZON_MILLIS,
                () -> ATTEMPT);
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void claimsDeadRowAndMarksPendingOnlyAfterBrokerConfirmation() {
        DeadLetterMessageRow dead = row(DeadLetterStatus.DEAD);
        DeadLetterMessageRow pending = row(DeadLetterStatus.PENDING);
        when(deadLetterMessageService.findById(ID)).thenReturn(dead, pending);
        when(deadLetterMessageService.claimReplay(
                ID, ATTEMPT, RECOVERY_HORIZON_MILLIS)).thenReturn(true);
        when(kafkaTemplate.send(
                "canal-outbox", "reaction-41", "{\"id\":41}"))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
        when(deadLetterMessageService.finishReplay(
                ID, ATTEMPT, DeadLetterStatus.PENDING)).thenReturn(true);

        DeadLetterReplayResult result = service.replay(ID);

        InOrder order = inOrder(deadLetterMessageService, kafkaTemplate);
        order.verify(deadLetterMessageService).findById(ID);
        order.verify(deadLetterMessageService).claimReplay(
                ID, ATTEMPT, RECOVERY_HORIZON_MILLIS);
        order.verify(kafkaTemplate).send(
                "canal-outbox", "reaction-41", "{\"id\":41}");
        order.verify(deadLetterMessageService).finishReplay(
                ID, ATTEMPT, DeadLetterStatus.PENDING);
        order.verify(deadLetterMessageService).findById(ID);
        assertThat(result.status()).isEqualTo(DeadLetterStatus.PENDING.name());
        assertThat(result.replayAttemptToken()).isEqualTo(ATTEMPT);
    }

    @Test
    void recoveryDeadlineCoversConfiguredProducerBlockAndDeliveryWindows() {
        when(kafkaTemplate.getProducerFactory()).thenReturn(producerFactory);
        when(producerFactory.getConfigurationProperties()).thenReturn(Map.of(
                ProducerConfig.MAX_BLOCK_MS_CONFIG, "7000",
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 11_000));
        service = new DeadLetterReplayService(
                deadLetterMessageService, kafkaTemplate);
        when(deadLetterMessageService.findById(ID))
                .thenReturn(
                        row(DeadLetterStatus.DEAD),
                        row(DeadLetterStatus.PENDING));
        when(deadLetterMessageService.claimReplay(
                eq(ID), anyString(), eq(48_000L))).thenReturn(true);
        when(kafkaTemplate.send(
                "canal-outbox", "reaction-41", "{\"id\":41}"))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
        when(deadLetterMessageService.finishReplay(
                eq(ID), anyString(), eq(DeadLetterStatus.PENDING)))
                .thenReturn(true);

        service.replay(ID);

        ArgumentCaptor<String> claimToken =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> finishToken =
                ArgumentCaptor.forClass(String.class);
        verify(deadLetterMessageService).claimReplay(
                eq(ID), claimToken.capture(), eq(48_000L));
        verify(deadLetterMessageService).finishReplay(
                eq(ID),
                finishToken.capture(),
                eq(DeadLetterStatus.PENDING));
        assertThat(finishToken.getValue()).isEqualTo(claimToken.getValue());
    }

    @Test
    void concurrentReplayCannotPublishWithoutWinningTheTokenizedClaim() {
        when(deadLetterMessageService.findById(ID))
                .thenReturn(
                        row(DeadLetterStatus.DEAD),
                        row(DeadLetterStatus.REPLAYING));
        when(deadLetterMessageService.claimReplay(
                ID, ATTEMPT, RECOVERY_HORIZON_MILLIS)).thenReturn(false);

        assertThatThrownBy(() -> service.replay(ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(DeadLetterStatus.REPLAYING.name());

        verify(kafkaTemplate, never()).send(
                "canal-outbox", "reaction-41", "{\"id\":41}");
    }

    @Test
    void synchronousSendFailureSafelyReturnsTheSameAttemptToDead() {
        prepareClaim();
        when(kafkaTemplate.send(
                "canal-outbox", "reaction-41", "{\"id\":41}"))
                .thenThrow(new IllegalStateException("producer closed"));
        when(deadLetterMessageService.finishReplay(
                ID, ATTEMPT, DeadLetterStatus.DEAD)).thenReturn(true);

        assertThatThrownBy(() -> service.replay(ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before broker confirmation");

        verify(deadLetterMessageService).finishReplay(
                ID, ATTEMPT, DeadLetterStatus.DEAD);
    }

    @Test
    void asynchronousFailureMovesTheFinishedAttemptToUncertain() {
        prepareClaim();
        CompletableFuture<SendResult<String, String>> failed =
                new CompletableFuture<>();
        failed.completeExceptionally(
                new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send(
                "canal-outbox", "reaction-41", "{\"id\":41}"))
                .thenReturn(failed);
        when(deadLetterMessageService.finishReplay(
                ID, ATTEMPT, DeadLetterStatus.UNCERTAIN)).thenReturn(true);

        assertThatThrownBy(() -> service.replay(ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outcome is uncertain");

        verify(deadLetterMessageService).finishReplay(
                ID, ATTEMPT, DeadLetterStatus.UNCERTAIN);
    }

    @Test
    void completedFutureWithoutSendResultMovesToUncertain() {
        prepareClaim();
        when(kafkaTemplate.send(
                "canal-outbox", "reaction-41", "{\"id\":41}"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(deadLetterMessageService.finishReplay(
                ID, ATTEMPT, DeadLetterStatus.UNCERTAIN)).thenReturn(true);

        assertThatThrownBy(() -> service.replay(ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outcome is uncertain");

        verify(deadLetterMessageService).finishReplay(
                ID, ATTEMPT, DeadLetterStatus.UNCERTAIN);
    }

    @Test
    void brokerAckStatusWriteFailureLeavesTokenizedClaimForExpiryRecovery() {
        prepareClaim();
        when(kafkaTemplate.send(
                "canal-outbox", "reaction-41", "{\"id\":41}"))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
        when(deadLetterMessageService.finishReplay(
                ID, ATTEMPT, DeadLetterStatus.PENDING))
                .thenThrow(
                        new DataAccessResourceFailureException(
                                "mysql unavailable"));

        assertThatThrownBy(() -> service.replay(ID))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("mysql unavailable");

        verify(deadLetterMessageService, never()).resolveUncertain(
                ID, ATTEMPT, DeadLetterStatus.DEAD);
    }

    @Test
    void httpTimeoutKeepsAttemptReplayingAndLateAckFinishesTheSameToken() {
        service = new DeadLetterReplayService(
                deadLetterMessageService,
                kafkaTemplate,
                1L,
                RECOVERY_HORIZON_MILLIS,
                () -> ATTEMPT);
        prepareClaim();
        CompletableFuture<SendResult<String, String>> confirmation =
                new CompletableFuture<>();
        when(kafkaTemplate.send(
                "canal-outbox", "reaction-41", "{\"id\":41}"))
                .thenReturn(confirmation);
        when(deadLetterMessageService.finishReplay(
                ID, ATTEMPT, DeadLetterStatus.PENDING)).thenReturn(true);

        assertThatThrownBy(() -> service.replay(ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still in progress");
        verify(deadLetterMessageService, never()).finishReplay(
                ID, ATTEMPT, DeadLetterStatus.UNCERTAIN);

        confirmation.complete(sendResult);

        verify(deadLetterMessageService).finishReplay(
                ID, ATTEMPT, DeadLetterStatus.PENDING);
    }

    @Test
    void interruptionLeavesCallbackActiveAndRestoresTheInterruptFlag() {
        prepareClaim();
        CompletableFuture<SendResult<String, String>> confirmation =
                new CompletableFuture<>();
        when(kafkaTemplate.send(
                "canal-outbox", "reaction-41", "{\"id\":41}"))
                .thenReturn(confirmation);
        when(deadLetterMessageService.finishReplay(
                ID, ATTEMPT, DeadLetterStatus.PENDING)).thenReturn(true);
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> service.replay(ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still in progress");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();

        Thread.interrupted();
        confirmation.complete(sendResult);
        verify(deadLetterMessageService).finishReplay(
                ID, ATTEMPT, DeadLetterStatus.PENDING);
    }

    @Test
    void staleAttemptCanOnlyRecoverToUncertainWithoutPublishing() {
        DeadLetterMessageRow uncertain = row(DeadLetterStatus.UNCERTAIN);
        when(deadLetterMessageService.recoverExpiredReplay(ID, ATTEMPT))
                .thenReturn(true);
        when(deadLetterMessageService.findById(ID)).thenReturn(uncertain);

        DeadLetterReplayResult result = service.recoverExpired(ID, ATTEMPT);

        assertThat(result.status())
                .isEqualTo(DeadLetterStatus.UNCERTAIN.name());
        verify(kafkaTemplate, never()).send(
                "canal-outbox", "reaction-41", "{\"id\":41}");
    }

    @Test
    void unexpiredAttemptCannotBeRecoveredOrResolved() {
        when(deadLetterMessageService.recoverExpiredReplay(ID, ATTEMPT))
                .thenReturn(false);
        when(deadLetterMessageService.findById(ID))
                .thenReturn(row(DeadLetterStatus.REPLAYING));

        assertThatThrownBy(() -> service.recoverExpired(ID, ATTEMPT))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(DeadLetterStatus.REPLAYING.name());
        assertThatThrownBy(() -> service.resolve(ID, ATTEMPT, false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void operatorCanResolveOnlyUncertainAttemptWithoutPublishingAgain() {
        DeadLetterMessageRow dead = row(DeadLetterStatus.DEAD);
        when(deadLetterMessageService.resolveUncertain(
                ID, ATTEMPT, DeadLetterStatus.DEAD)).thenReturn(true);
        when(deadLetterMessageService.findById(ID)).thenReturn(dead);

        DeadLetterReplayResult result = service.resolve(ID, ATTEMPT, false);

        assertThat(result.status()).isEqualTo(DeadLetterStatus.DEAD.name());
        verify(kafkaTemplate, never()).send(
                "canal-outbox", "reaction-41", "{\"id\":41}");
    }

    @Test
    void verifiedPublicationResolvesToPendingWithoutPublishingAgain() {
        DeadLetterMessageRow pending = row(DeadLetterStatus.PENDING);
        when(deadLetterMessageService.resolveUncertain(
                ID, ATTEMPT, DeadLetterStatus.PENDING)).thenReturn(true);
        when(deadLetterMessageService.findById(ID)).thenReturn(pending);

        DeadLetterReplayResult result = service.resolve(ID, ATTEMPT, true);

        assertThat(result.status()).isEqualTo(DeadLetterStatus.PENDING.name());
        assertThat(result.replayAttemptToken()).isEqualTo(ATTEMPT);
        verify(deadLetterMessageService).resolveUncertain(
                ID, ATTEMPT, DeadLetterStatus.PENDING);
        verify(kafkaTemplate, never()).send(
                "canal-outbox", "reaction-41", "{\"id\":41}");
    }

    @Test
    void oldAttemptTokenCannotCompleteARecoveredOrNewerReplay() {
        prepareClaim();
        when(kafkaTemplate.send(
                "canal-outbox", "reaction-41", "{\"id\":41}"))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
        when(deadLetterMessageService.finishReplay(
                ID, ATTEMPT, DeadLetterStatus.PENDING)).thenReturn(false);

        assertThatThrownBy(() -> service.replay(ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer owns");
    }

    @Test
    void rejectsMissingReplayRowBeforeClaimingOrPublishing() {
        when(deadLetterMessageService.findById(ID)).thenReturn(null);

        assertThatThrownBy(() -> service.replay(ID))
                .isInstanceOf(BusinessException.class);

        verify(deadLetterMessageService, never()).claimReplay(
                eq(ID), anyString(), eq(RECOVERY_HORIZON_MILLIS));
        verify(kafkaTemplate, never()).send(
                "canal-outbox", "reaction-41", "{\"id\":41}");
    }

    private void prepareClaim() {
        when(deadLetterMessageService.findById(ID))
                .thenReturn(row(DeadLetterStatus.DEAD));
        when(deadLetterMessageService.claimReplay(
                ID, ATTEMPT, RECOVERY_HORIZON_MILLIS)).thenReturn(true);
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
