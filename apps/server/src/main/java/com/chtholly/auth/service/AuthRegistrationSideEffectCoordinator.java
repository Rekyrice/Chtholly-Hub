package com.chtholly.auth.service;

import com.chtholly.auth.audit.LoginLogService;
import com.chtholly.auth.event.UserRegisteredEvent;
import com.chtholly.auth.model.ClientInfo;
import com.chtholly.user.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Defers non-authoritative registration side effects until MySQL commits. */
@Component
public class AuthRegistrationSideEffectCoordinator {

    private static final Logger log = LoggerFactory.getLogger(
            AuthRegistrationSideEffectCoordinator.class);

    private final LoginLogService loginLogService;
    private final ApplicationEventPublisher eventPublisher;

    /** Creates the registration side-effect coordinator. */
    public AuthRegistrationSideEffectCoordinator(
            LoginLogService loginLogService,
            ApplicationEventPublisher eventPublisher) {
        this.loginLogService = loginLogService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Schedules audit and domain-event publication after a successful commit.
     *
     * @param user newly created user
     * @param auditIdentifier normalized registration identifier
     * @param clientInfo request client metadata
     */
    public void afterCommit(
            User user,
            String auditIdentifier,
            ClientInfo clientInfo) {
        requireRegistrationTransaction();
        UserRegisteredEvent event = new UserRegisteredEvent(user);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        recordAuditBestEffort(
                                user.getId(), auditIdentifier, clientInfo);
                        publishEventBestEffort(event);
                    }
                });
    }

    private void recordAuditBestEffort(
            Long userId,
            String identifier,
            ClientInfo clientInfo) {
        try {
            loginLogService.recordSuccess(
                    userId,
                    identifier,
                    "REGISTER",
                    clientInfo.ip(),
                    clientInfo.userAgent());
        } catch (Exception failure) {
            log.warn(
                    "Registration audit write failed, errorType={}",
                    failure.getClass().getSimpleName());
        }
    }

    private void publishEventBestEffort(UserRegisteredEvent event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception failure) {
            log.warn(
                    "UserRegisteredEvent failed, userId={}, errorType={}",
                    event.user().getId(),
                    failure.getClass().getSimpleName());
        }
    }

    private static void requireRegistrationTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager
                        .isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Registration side effects require an active transaction");
        }
    }
}
