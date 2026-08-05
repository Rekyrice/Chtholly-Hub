package com.chtholly.auth.service;

import com.chtholly.admin.security.UserBanService;
import com.chtholly.auth.api.dto.LoginRequest;
import com.chtholly.auth.api.dto.RegisterRequest;
import com.chtholly.auth.audit.LoginLogService;
import com.chtholly.auth.config.AuthProperties;
import com.chtholly.auth.model.IdentifierType;
import com.chtholly.auth.security.LoginFailureGuard;
import com.chtholly.auth.token.JwtService;
import com.chtholly.auth.token.PendingUserRefreshTokenStore;
import com.chtholly.auth.token.RefreshTokenStore;
import com.chtholly.auth.token.TokenPair;
import com.chtholly.auth.verification.VerificationService;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.user.domain.User;
import com.chtholly.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceHandleTest {

    @Mock private UserService userService;
    @Mock private VerificationService verificationService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private RefreshTokenStore refreshTokenStore;
    @Mock private PendingUserRefreshTokenStore pendingUserRefreshTokenStore;
    @Mock private LoginLogService loginLogService;
    @Mock private LoginFailureGuard loginFailureGuard;
    @Mock private AuthProperties authProperties;
    @Mock private UserBanService userBanService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuthRegistrationSideEffectCoordinator registrationSideEffects;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        AuthIdentityPolicy identityPolicy =
                new AuthIdentityPolicy(userService, authProperties);
        AuthTokenLifecycleService tokenLifecycleService =
                new AuthTokenLifecycleService(
                        jwtService,
                        refreshTokenStore,
                        pendingUserRefreshTokenStore,
                        userService,
                        userBanService);
        authService = new AuthService(
                new AuthVerificationCodeService(
                        identityPolicy, verificationService),
                new AuthRegistrationService(
                        userService,
                        verificationService,
                        passwordEncoder,
                        identityPolicy,
                        tokenLifecycleService,
                        registrationSideEffects),
                new AuthLoginService(
                        verificationService,
                        passwordEncoder,
                        identityPolicy,
                        tokenLifecycleService,
                        loginLogService,
                        loginFailureGuard,
                        userBanService),
                tokenLifecycleService,
                new AuthPasswordRecoveryService(
                        userService,
                        verificationService,
                        passwordEncoder,
                        identityPolicy),
                new AuthCurrentUserService(identityPolicy));
        lenient().when(refreshTokenStore.captureEpoch(anyLong()))
                .thenReturn(1L);
        lenient().when(refreshTokenStore.storeTokenIfEpochMatches(
                        anyLong(), anyString(), any(), eq(1L)))
                .thenReturn(true);
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private void stubPasswordPolicy() {
        AuthProperties.Password password = new AuthProperties.Password();
        password.setMinLength(8);
        when(authProperties.getPassword()).thenReturn(password);
    }

    @Test
    void registerWithHandleStoresBcryptPassword() {
        stubPasswordPolicy();
        when(userService.existsByHandle("alice")).thenReturn(false);
        when(passwordEncoder.encode("Pass1234")).thenReturn("$2a$hash");
        User saved = User.builder().id(10L).handle("alice").nickname("Chtholly用户abcd1234").build();
        when(userService.createUser(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(10L);
            return u;
        });
        when(jwtService.issueTokenPair(any(User.class))).thenReturn(new TokenPair(
                "access", Instant.now().plusSeconds(3600), "refresh", Instant.now().plusSeconds(86400), "jti"));

        RegisterRequest request = new RegisterRequest(
                IdentifierType.HANDLE, null, "alice", null, "Pass1234", null, true);

        beginTransaction();
        authService.register(request, new com.chtholly.auth.model.ClientInfo("127.0.0.1", "test"));
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_COMMITTED));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(captor.capture());
        assertEquals("alice", captor.getValue().getHandle());
        assertEquals("$2a$hash", captor.getValue().getPasswordHash());
    }

    @Test
    void registerWithDuplicateHandleThrows() {
        when(userService.existsByHandle("alice")).thenReturn(true);

        RegisterRequest request = new RegisterRequest(
                IdentifierType.HANDLE, null, "alice", null, "Pass1234", null, true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register(request, new com.chtholly.auth.model.ClientInfo("127.0.0.1", "test")));
        assertEquals(ErrorCode.HANDLE_EXISTS, ex.getErrorCode());
    }

    @Test
    void loginWithHandleAndPasswordIssuesTokens() {
        User user = User.builder()
                .id(1L)
                .handle("alice")
                .passwordHash("$2a$hash")
                .nickname("Alice")
                .build();
        when(userService.findByHandle("alice")).thenReturn(Optional.of(user));
        when(userService.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Pass1234", "$2a$hash")).thenReturn(true);
        when(jwtService.issueTokenPair(user)).thenReturn(new TokenPair(
                "access", Instant.now().plusSeconds(3600), "refresh", Instant.now().plusSeconds(86400), "jti"));

        LoginRequest request = new LoginRequest(IdentifierType.HANDLE, "alice", null, "Pass1234");
        authService.login(request, new com.chtholly.auth.model.ClientInfo("127.0.0.1", "test"));

        verify(loginFailureGuard).onSuccess("alice");
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
}
