package com.chtholly.auth.service;

import com.chtholly.admin.security.UserBanService;
import com.chtholly.auth.api.dto.RegisterRequest;
import com.chtholly.auth.model.ClientInfo;
import com.chtholly.auth.token.JwtService;
import com.chtholly.auth.token.PendingUserRefreshTokenStore;
import com.chtholly.auth.token.RefreshTokenStore;
import com.chtholly.auth.token.TokenPair;
import com.chtholly.user.domain.User;
import com.chtholly.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthPendingRegistrationTransactionTest {

    @Mock private JwtService jwtService;
    @Mock private RefreshTokenStore refreshTokenStore;
    @Mock private PendingUserRefreshTokenStore pendingUserRefreshTokenStore;
    @Mock private UserService userService;
    @Mock private UserBanService userBanService;

    private AuthTokenLifecycleService tokenLifecycleService;

    @BeforeEach
    void setUp() {
        tokenLifecycleService = new AuthTokenLifecycleService(
                jwtService,
                refreshTokenStore,
                pendingUserRefreshTokenStore,
                userService,
                userBanService);
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void pendingIssuanceRejectsCallsOutsideARegistrationTransaction() {
        User user = User.builder().id(7L).handle("member").build();

        assertThatThrownBy(() ->
                tokenLifecycleService.issueForPendingRegistration(user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");

        verifyNoInteractions(jwtService, pendingUserRefreshTokenStore);
    }

    @Test
    void rollbackDiscardsAStoredPendingMembership() {
        beginTransaction();
        User user = User.builder().id(7L).handle("member").build();
        when(jwtService.issueTokenPair(user)).thenReturn(tokenPair());

        var response = tokenLifecycleService.issueForPendingRegistration(user);

        assertThat(response.refreshToken()).isEqualTo("refresh");
        verify(pendingUserRefreshTokenStore).storeInitialTokenForPendingUser(
                eq(7L), eq("registration-jti"), any());
        runAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(pendingUserRefreshTokenStore)
                .discardInitialTokenForPendingUser(7L, "registration-jti");
    }

    @Test
    void committedRegistrationKeepsThePendingMembership() {
        beginTransaction();
        User user = User.builder().id(7L).handle("member").build();
        when(jwtService.issueTokenPair(user)).thenReturn(tokenPair());

        tokenLifecycleService.issueForPendingRegistration(user);
        runAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verify(pendingUserRefreshTokenStore, never())
                .discardInitialTokenForPendingUser(7L, "registration-jti");
    }

    @Test
    void unknownCompletionDiscardsThePendingMembershipBestEffort() {
        beginTransaction();
        User user = User.builder().id(7L).handle("member").build();
        when(jwtService.issueTokenPair(user)).thenReturn(tokenPair());

        tokenLifecycleService.issueForPendingRegistration(user);
        runAfterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

        verify(pendingUserRefreshTokenStore)
                .discardInitialTokenForPendingUser(7L, "registration-jti");
    }

    @Test
    void registrationEntryPointOwnsTheRequiredMysqlTransaction()
            throws Exception {
        Transactional transactional = AuthRegistrationService.class
                .getMethod(
                        "register",
                        RegisterRequest.class,
                        ClientInfo.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation())
                .isEqualTo(Propagation.REQUIRED);
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static void runAfterCompletion(int status) {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization ->
                        synchronization.afterCompletion(status));
    }

    private static TokenPair tokenPair() {
        Instant now = Instant.now();
        return new TokenPair(
                "access",
                now.plusSeconds(60),
                "refresh",
                now.plusSeconds(300),
                "registration-jti");
    }
}
