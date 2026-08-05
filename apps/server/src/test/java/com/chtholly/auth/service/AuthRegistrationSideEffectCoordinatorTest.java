package com.chtholly.auth.service;

import com.chtholly.auth.audit.LoginLogService;
import com.chtholly.auth.model.ClientInfo;
import com.chtholly.user.domain.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AuthRegistrationSideEffectCoordinatorTest {

    @Mock private LoginLogService loginLogService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AuthRegistrationSideEffectCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new AuthRegistrationSideEffectCoordinator(
                loginLogService, eventPublisher);
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void schedulingRequiresAnActiveRegistrationTransaction() {
        assertThatThrownBy(() -> coordinator.afterCommit(
                user(), "member", clientInfo()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");

        verifyNoInteractions(loginLogService, eventPublisher);
    }

    @Test
    void auditAndEventRunOnlyAfterCommitAndRemainIndependent() {
        beginTransaction();
        doThrow(new IllegalStateException("audit unavailable"))
                .when(loginLogService)
                .recordSuccess(
                        7L,
                        "member",
                        "REGISTER",
                        "127.0.0.1",
                        "test");
        doThrow(new IllegalStateException("listener unavailable"))
                .when(eventPublisher)
                .publishEvent(any(Object.class));

        coordinator.afterCommit(user(), "member", clientInfo());

        verifyNoInteractions(loginLogService, eventPublisher);
        assertThatCode(AuthRegistrationSideEffectCoordinatorTest::runAfterCommit)
                .doesNotThrowAnyException();
        verify(loginLogService).recordSuccess(
                7L,
                "member",
                "REGISTER",
                "127.0.0.1",
                "test");
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void rollbackPublishesNeitherAuditNorEvent() {
        beginTransaction();
        coordinator.afterCommit(user(), "member", clientInfo());

        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(loginLogService, never()).recordSuccess(
                7L,
                "member",
                "REGISTER",
                "127.0.0.1",
                "test");
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    private static User user() {
        return User.builder().id(7L).handle("member").build();
    }

    private static ClientInfo clientInfo() {
        return new ClientInfo("127.0.0.1", "test");
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static void runAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }
}
