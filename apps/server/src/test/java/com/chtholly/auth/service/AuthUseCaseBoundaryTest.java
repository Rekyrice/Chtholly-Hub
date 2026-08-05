package com.chtholly.auth.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chtholly.admin.security.UserBanService;
import com.chtholly.auth.api.dto.AuthResponse;
import com.chtholly.auth.api.dto.PasswordResetRequest;
import com.chtholly.auth.api.dto.LoginRequest;
import com.chtholly.auth.api.dto.RegisterRequest;
import com.chtholly.auth.api.dto.SendCodeRequest;
import com.chtholly.auth.api.dto.TokenRefreshRequest;
import com.chtholly.auth.audit.LoginFailureReason;
import com.chtholly.auth.audit.LoginLogService;
import com.chtholly.auth.config.AuthProperties;
import com.chtholly.auth.model.ClientInfo;
import com.chtholly.auth.model.IdentifierType;
import com.chtholly.auth.security.LoginFailureGuard;
import com.chtholly.auth.token.JwtService;
import com.chtholly.auth.token.PendingUserRefreshTokenStore;
import com.chtholly.auth.token.RefreshTokenStore;
import com.chtholly.auth.token.TokenPair;
import com.chtholly.auth.verification.VerificationCheckResult;
import com.chtholly.auth.verification.VerificationCodeStatus;
import com.chtholly.auth.verification.SendCodeResult;
import com.chtholly.auth.verification.VerificationScene;
import com.chtholly.auth.verification.VerificationService;
import com.chtholly.user.domain.User;
import com.chtholly.user.service.UserService;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/** Characterizes the token and password use-case boundaries extracted from the facade. */
@ExtendWith(MockitoExtension.class)
class AuthUseCaseBoundaryTest {

    @Mock private UserService userService;
    @Mock private VerificationService verificationService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private RefreshTokenStore refreshTokenStore;
    @Mock private PendingUserRefreshTokenStore pendingUserRefreshTokenStore;
    @Mock private LoginLogService loginLogService;
    @Mock private LoginFailureGuard loginFailureGuard;
    @Mock private UserBanService userBanService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AuthTokenLifecycleService tokenLifecycleService;
    private AuthPasswordRecoveryService passwordRecoveryService;
    private AuthIdentityPolicy identityPolicy;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.getPassword().setMinLength(8);
        identityPolicy = new AuthIdentityPolicy(userService, properties);
        tokenLifecycleService = new AuthTokenLifecycleService(
                jwtService,
                refreshTokenStore,
                pendingUserRefreshTokenStore,
                userService,
                userBanService);
        passwordRecoveryService = new AuthPasswordRecoveryService(
                userService,
                verificationService,
                passwordEncoder,
                identityPolicy);
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

    @Test
    void sendCodeNormalizesEmailBeforeExistenceCheckAndDelivery() {
        when(userService.existsByEmail("owner@example.com"))
                .thenReturn(true);
        when(verificationService.sendCode(
                VerificationScene.LOGIN, "owner@example.com"))
                .thenReturn(new SendCodeResult(
                        "owner@example.com", VerificationScene.LOGIN, 300));
        AuthVerificationCodeService service =
                new AuthVerificationCodeService(
                        identityPolicy, verificationService);

        var response = service.sendCode(new SendCodeRequest(
                VerificationScene.LOGIN,
                IdentifierType.EMAIL,
                "OWNER@EXAMPLE.COM"));

        assertThat(response.identifier()).isEqualTo("owner@example.com");
        verify(verificationService).sendCode(
                VerificationScene.LOGIN, "owner@example.com");
    }

    @Test
    void wrongPasswordUpdatesFailureGuardBeforeBestEffortAudit() {
        User user = User.builder()
                .id(7L)
                .handle("owner")
                .passwordHash("encoded")
                .build();
        when(userService.findByHandle("owner"))
                .thenReturn(Optional.of(user));
        when(userService.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded"))
                .thenReturn(false);
        AuthLoginService service = new AuthLoginService(
                verificationService,
                passwordEncoder,
                identityPolicy,
                tokenLifecycleService,
                loginLogService,
                loginFailureGuard,
                userBanService);

        assertThatThrownBy(() -> service.login(
                new LoginRequest(
                        IdentifierType.HANDLE,
                        "owner",
                        null,
                        "wrong"),
                new com.chtholly.auth.model.ClientInfo("127.0.0.1", "test")))
                .isInstanceOf(com.chtholly.common.exception.BusinessException.class)
                .extracting(failure ->
                        ((com.chtholly.common.exception.BusinessException) failure)
                                .getErrorCode())
                .isEqualTo(com.chtholly.common.exception.ErrorCode.INVALID_CREDENTIALS);

        InOrder order = inOrder(loginLogService, loginFailureGuard);
        order.verify(loginFailureGuard).onFailure("owner", "127.0.0.1");
        order.verify(loginLogService).recordFailure(
                7L,
                "owner",
                "PASSWORD",
                "127.0.0.1",
                "test",
                LoginFailureReason.WRONG_PASSWORD);
        verify(jwtService, never()).issueTokenPair(user);
    }

    @Test
    void wrongPasswordStillRejectsWhenAuditStorageIsUnavailable() {
        User user = User.builder()
                .id(7L)
                .handle("owner")
                .passwordHash("encoded")
                .build();
        when(userService.findByHandle("owner"))
                .thenReturn(Optional.of(user));
        when(userService.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded"))
                .thenReturn(false);
        doThrow(new IllegalStateException("audit database unavailable"))
                .when(loginLogService)
                .recordFailure(
                        7L,
                        "owner",
                        "PASSWORD",
                        "127.0.0.1",
                        "test",
                        LoginFailureReason.WRONG_PASSWORD);

        assertThatThrownBy(() -> loginService().login(
                new LoginRequest(
                        IdentifierType.HANDLE,
                        "owner",
                        null,
                        "wrong"),
                new ClientInfo("127.0.0.1", "test")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        verify(loginFailureGuard).onFailure("owner", "127.0.0.1");
        verify(jwtService, never()).issueTokenPair(user);
    }

    @Test
    void userBanIsCheckedBeforeGuardSuccessAndTokenIssuance() {
        User user = User.builder()
                .id(7L)
                .handle("owner")
                .passwordHash("encoded")
                .build();
        when(userService.findByHandle("owner"))
                .thenReturn(Optional.of(user));
        when(userService.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Pass1234", "encoded"))
                .thenReturn(true);
        doThrow(new com.chtholly.common.exception.BusinessException(
                com.chtholly.common.exception.ErrorCode.USER_BANNED))
                .when(userBanService)
                .assertNotBanned(user);
        AuthLoginService service = new AuthLoginService(
                verificationService,
                passwordEncoder,
                identityPolicy,
                tokenLifecycleService,
                loginLogService,
                loginFailureGuard,
                userBanService);

        assertThatThrownBy(() -> service.login(
                new LoginRequest(
                        IdentifierType.HANDLE,
                        "owner",
                        null,
                        "Pass1234"),
                new com.chtholly.auth.model.ClientInfo("127.0.0.1", "test")))
                .isInstanceOf(com.chtholly.common.exception.BusinessException.class);

        verify(loginFailureGuard, never()).onSuccess("owner");
        verify(jwtService, never()).issueTokenPair(user);
        verify(loginLogService, never()).recordSuccess(
                7L, "owner", "PASSWORD", "127.0.0.1", "test");
        verify(loginLogService).recordFailure(
                7L,
                "owner",
                "PASSWORD",
                "127.0.0.1",
                "test",
                LoginFailureReason.USER_BANNED);
    }

    @Test
    void registrationKeepsTokenAndContinuesWhenAfterCommitEffectsFail() {
        when(userService.existsByHandle("owner")).thenReturn(false);
        when(passwordEncoder.encode("Pass1234")).thenReturn("encoded");
        when(userService.createUser(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(7L);
            return user;
        });
        when(jwtService.issueTokenPair(any(User.class)))
                .thenReturn(tokenPair("new-jti"));
        doThrow(new IllegalStateException("audit down"))
                .when(loginLogService)
                .recordSuccess(
                        7L,
                        "owner",
                        "REGISTER",
                        "127.0.0.1",
                        "test");
        doThrow(new IllegalStateException("listener down"))
                .when(eventPublisher)
                .publishEvent(any(Object.class));
        AuthRegistrationSideEffectCoordinator sideEffects =
                new AuthRegistrationSideEffectCoordinator(
                        loginLogService, eventPublisher);
        AuthRegistrationService service = new AuthRegistrationService(
                userService,
                verificationService,
                passwordEncoder,
                identityPolicy,
                tokenLifecycleService,
                sideEffects);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                        AuthRegistrationSideEffectCoordinator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        AuthResponse response;
        beginTransaction();
        try {
            response = service.register(
                    new RegisterRequest(
                            IdentifierType.HANDLE,
                            null,
                            "owner",
                            null,
                            "Pass1234",
                            "Owner",
                            true),
                    new ClientInfo("127.0.0.1", "test"));
            verifyNoInteractions(loginLogService, eventPublisher);
            runAfterCommit();
            runAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        InOrder order = inOrder(
                userService,
                jwtService,
                pendingUserRefreshTokenStore,
                loginLogService,
                eventPublisher);
        order.verify(userService).createUser(any(User.class));
        order.verify(jwtService).issueTokenPair(any(User.class));
        order.verify(pendingUserRefreshTokenStore)
                .storeInitialTokenForPendingUser(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("new-jti"),
                org.mockito.ArgumentMatchers.any());
        order.verify(loginLogService).recordSuccess(
                7L, "owner", "REGISTER", "127.0.0.1", "test");
        order.verify(eventPublisher).publishEvent(any(Object.class));
        assertThat(response.user().id()).isEqualTo(7L);
        assertThat(response.token().refreshToken()).isEqualTo("refresh");
        assertThat(appender.list)
                .hasSize(2)
                .allSatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("IllegalStateException")
                        .doesNotContain("audit down")
                        .doesNotContain("listener down"));
    }

    @Test
    void registrationHashesThePasswordExactlyAsProvided() {
        when(userService.existsByHandle("owner")).thenReturn(false);
        when(passwordEncoder.encode(" Pass1234 ")).thenReturn("encoded");
        when(userService.createUser(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(7L);
            return user;
        });
        when(jwtService.issueTokenPair(any(User.class)))
                .thenReturn(tokenPair("new-jti"));

        beginTransaction();
        registrationService().register(
                new RegisterRequest(
                        IdentifierType.HANDLE,
                        null,
                        "owner",
                        null,
                        " Pass1234 ",
                        "Owner",
                        true),
                new ClientInfo("127.0.0.1", "test"));
        runAfterCommit();
        runAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verify(passwordEncoder).encode(" Pass1234 ");
    }

    @Test
    void verifiedRegistrationRejectsAnAllWhitespaceOptionalPassword() {
        when(userService.existsByEmail("owner@example.com"))
                .thenReturn(false);
        when(verificationService.verify(
                VerificationScene.REGISTER,
                "owner@example.com",
                "123456"))
                .thenReturn(new VerificationCheckResult(
                        VerificationCodeStatus.SUCCESS, 0, 5));

        assertThatThrownBy(() -> registrationService().register(
                new RegisterRequest(
                        IdentifierType.EMAIL,
                        "owner@example.com",
                        null,
                        "123456",
                        "        ",
                        null,
                        true),
                new ClientInfo("127.0.0.1", "test")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.PASSWORD_POLICY_VIOLATION);

        verify(userService, never()).createUser(any(User.class));
    }

    @Test
    void refreshAtomicallyRotatesWhitelistAfterIssuingReplacementPair() {
        Jwt jwt = jwt("refresh", 7L, "old-jti");
        User user = User.builder().id(7L).nickname("七号").build();
        TokenPair replacement = tokenPair("new-jti");
        when(jwtService.decode("old-refresh")).thenReturn(jwt);
        when(jwtService.extractTokenType(jwt)).thenReturn("refresh");
        when(jwtService.extractUserId(jwt)).thenReturn(7L);
        when(jwtService.extractTokenId(jwt)).thenReturn("old-jti");
        when(refreshTokenStore.isTokenValid(7L, "old-jti")).thenReturn(true);
        when(userService.findById(7L)).thenReturn(Optional.of(user));
        when(jwtService.issueTokenPair(user)).thenReturn(replacement);
        when(refreshTokenStore.rotateToken(
                eq(7L),
                eq("old-jti"),
                eq("new-jti"),
                any())).thenReturn(true);

        var response = tokenLifecycleService.refresh(
                new TokenRefreshRequest("old-refresh"));

        InOrder order = inOrder(jwtService, refreshTokenStore);
        order.verify(jwtService).issueTokenPair(user);
        order.verify(refreshTokenStore).rotateToken(
                eq(7L), eq("old-jti"), eq("new-jti"), any());
        verify(refreshTokenStore, never()).revokeToken(7L, "old-jti");
        assertThat(response.refreshToken()).isEqualTo("refresh");
    }

    @Test
    void refreshRejectsReplacementWhenAtomicRotationLosesTheRace() {
        Jwt jwt = jwt("refresh", 7L, "old-jti");
        User user = User.builder().id(7L).nickname("seven").build();
        when(jwtService.decode("old-refresh")).thenReturn(jwt);
        when(jwtService.extractTokenType(jwt)).thenReturn("refresh");
        when(jwtService.extractUserId(jwt)).thenReturn(7L);
        when(jwtService.extractTokenId(jwt)).thenReturn("old-jti");
        when(refreshTokenStore.isTokenValid(7L, "old-jti")).thenReturn(true);
        when(userService.findById(7L)).thenReturn(Optional.of(user));
        when(jwtService.issueTokenPair(user)).thenReturn(tokenPair("new-jti"));
        when(refreshTokenStore.rotateToken(
                eq(7L),
                eq("old-jti"),
                eq("new-jti"),
                any())).thenReturn(false);

        assertThatThrownBy(() -> tokenLifecycleService.refresh(
                new TokenRefreshRequest("old-refresh")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);

        verify(refreshTokenStore, never())
                .storeToken(anyLong(), anyString(), any());
    }

    @Test
    void refreshForDeletedUserUsesRefreshTokenInvalidError() {
        Jwt jwt = jwt("refresh", 7L, "orphan-jti");
        when(jwtService.decode("orphan-refresh")).thenReturn(jwt);
        when(jwtService.extractTokenType(jwt)).thenReturn("refresh");
        when(jwtService.extractUserId(jwt)).thenReturn(7L);
        when(jwtService.extractTokenId(jwt)).thenReturn("orphan-jti");
        when(refreshTokenStore.isTokenValid(7L, "orphan-jti"))
                .thenReturn(true);
        when(userService.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tokenLifecycleService.refresh(
                new TokenRefreshRequest("orphan-refresh")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);

        verify(jwtService, never()).issueTokenPair(any(User.class));
    }

    @Test
    void malformedRefreshAndLogoutAreRejectedWithoutLoggingTheToken() {
        when(jwtService.decode("secret-refresh-token"))
                .thenThrow(new JwtException(
                        "decoder exposed secret-refresh-token"));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                        AuthTokenLifecycleService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> tokenLifecycleService.refresh(
                    new TokenRefreshRequest("secret-refresh-token")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(failure ->
                            ((BusinessException) failure).getErrorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);
            tokenLifecycleService.logout("secret-refresh-token");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .hasSize(2)
                .allSatisfy(event -> {
                    assertThat(event.getFormattedMessage())
                            .contains("JwtException")
                            .doesNotContain("secret-refresh-token");
                    assertThat(event.getThrowableProxy()).isNull();
                });
        verify(refreshTokenStore, never())
                .isTokenValid(anyLong(), anyString());
        verify(userService, never()).findById(anyLong());
    }

    @Test
    void accessTokenCannotEnterRefreshWhitelistLookup() {
        Jwt jwt = jwt("access", 7L, "access-jti");
        when(jwtService.decode("access-token")).thenReturn(jwt);
        when(jwtService.extractTokenType(jwt)).thenReturn("access");

        assertThatThrownBy(() -> tokenLifecycleService.refresh(
                new TokenRefreshRequest("access-token")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);

        verify(refreshTokenStore, never())
                .isTokenValid(anyLong(), anyString());
        verify(userService, never()).findById(anyLong());
    }

    @Test
    void revokedRefreshTokenStopsBeforeUserLookup() {
        Jwt jwt = jwt("refresh", 7L, "revoked-jti");
        when(jwtService.decode("revoked-token")).thenReturn(jwt);
        when(jwtService.extractTokenType(jwt)).thenReturn("refresh");
        when(jwtService.extractUserId(jwt)).thenReturn(7L);
        when(jwtService.extractTokenId(jwt)).thenReturn("revoked-jti");
        when(refreshTokenStore.isTokenValid(7L, "revoked-jti"))
                .thenReturn(false);

        assertThatThrownBy(() -> tokenLifecycleService.refresh(
                new TokenRefreshRequest("revoked-token")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);

        verify(userService, never()).findById(anyLong());
        verify(jwtService, never()).issueTokenPair(any(User.class));
    }

    @Test
    void bannedRefreshRevokesThePresentedTokenWithoutIssuingAReplacement() {
        Jwt jwt = jwt("refresh", 7L, "banned-jti");
        User user = User.builder()
                .id(7L)
                .bannedAt(Instant.now())
                .build();
        when(jwtService.decode("banned-token")).thenReturn(jwt);
        when(jwtService.extractTokenType(jwt)).thenReturn("refresh");
        when(jwtService.extractUserId(jwt)).thenReturn(7L);
        when(jwtService.extractTokenId(jwt)).thenReturn("banned-jti");
        when(refreshTokenStore.isTokenValid(7L, "banned-jti"))
                .thenReturn(true);
        when(userService.findById(7L)).thenReturn(Optional.of(user));
        when(userBanService.bannedException())
                .thenReturn(new BusinessException(ErrorCode.USER_BANNED));

        assertThatThrownBy(() -> tokenLifecycleService.refresh(
                new TokenRefreshRequest("banned-token")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.USER_BANNED);

        verify(refreshTokenStore).revokeToken(7L, "banned-jti");
        verify(jwtService, never()).issueTokenPair(user);
    }

    @Test
    void logoutIgnoresAccessTokens() {
        Jwt jwt = jwt("access", 7L, "access-jti");
        when(jwtService.decode("access-token")).thenReturn(jwt);
        when(jwtService.extractTokenType(jwt)).thenReturn("access");

        tokenLifecycleService.logout("access-token");

        verify(refreshTokenStore, never())
                .revokeToken(anyLong(), anyString());
    }

    @Test
    void verificationCodeFailureIsCountedBeforeBestEffortAudit() {
        User user = User.builder().id(7L).email("owner@example.com").build();
        when(userService.findByEmail("owner@example.com"))
                .thenReturn(Optional.of(user));
        when(userService.findById(7L)).thenReturn(Optional.of(user));
        when(verificationService.verify(
                VerificationScene.LOGIN,
                "owner@example.com",
                "123456"))
                .thenReturn(new VerificationCheckResult(
                        VerificationCodeStatus.MISMATCH, 1, 5));
        AuthLoginService service = loginService();

        assertThatThrownBy(() -> service.login(
                new LoginRequest(
                        IdentifierType.EMAIL,
                        "OWNER@EXAMPLE.COM",
                        "123456",
                        null),
                new ClientInfo("127.0.0.1", "test")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        InOrder order = inOrder(loginLogService, loginFailureGuard);
        order.verify(loginFailureGuard)
                .onFailure("owner@example.com", "127.0.0.1");
        order.verify(loginLogService).recordFailure(
                7L,
                "owner@example.com",
                "CODE",
                "127.0.0.1",
                "test",
                null);
    }

    @Test
    void missingAccountUsesInvalidCredentialsAndCountsEveryLoginChannel() {
        when(userService.findByHandle("missing"))
                .thenReturn(Optional.empty());
        AuthLoginService service = loginService();

        assertThatThrownBy(() -> service.login(
                new LoginRequest(
                        IdentifierType.HANDLE,
                        "missing",
                        null,
                        "Pass1234"),
                new ClientInfo("127.0.0.1", "test")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThatThrownBy(() -> service.login(
                new LoginRequest(
                        IdentifierType.HANDLE,
                        "missing",
                        "123456",
                        null),
                new ClientInfo("127.0.0.1", "test")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        verify(loginFailureGuard, times(2))
                .onFailure("missing", "127.0.0.1");
        verify(loginLogService).recordFailure(
                null,
                "missing",
                "PASSWORD",
                "127.0.0.1",
                "test",
                LoginFailureReason.ACCOUNT_NOT_FOUND);
        verify(loginLogService).recordFailure(
                null,
                "missing",
                "CODE",
                "127.0.0.1",
                "test",
                LoginFailureReason.ACCOUNT_NOT_FOUND);
    }

    @Test
    void lockedCodeLoginAuditsTheActualChannelWithoutChangingTheError() {
        doThrow(new BusinessException(ErrorCode.LOGIN_LOCKED))
                .when(loginFailureGuard)
                .assertNotLocked("owner@example.com", "127.0.0.1");

        assertThatThrownBy(() -> loginService().login(
                new LoginRequest(
                        IdentifierType.EMAIL,
                        "owner@example.com",
                        "123456",
                        null),
                new ClientInfo("127.0.0.1", "test")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_LOCKED);

        verify(loginLogService).recordFailure(
                null,
                "owner@example.com",
                "CODE",
                "127.0.0.1",
                "test",
                LoginFailureReason.ACCOUNT_LOCKED);
    }

    @Test
    void successfulLoginReturnsTokensWhenAuditStorageIsUnavailable() {
        User user = User.builder()
                .id(7L)
                .handle("owner")
                .passwordHash("encoded")
                .build();
        when(userService.findByHandle("owner"))
                .thenReturn(Optional.of(user));
        when(userService.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Pass1234", "encoded"))
                .thenReturn(true);
        when(jwtService.issueTokenPair(user)).thenReturn(tokenPair("new-jti"));
        doThrow(new IllegalStateException("audit database unavailable"))
                .when(loginLogService)
                .recordSuccess(
                        7L,
                        "owner",
                        "PASSWORD",
                        "127.0.0.1",
                        "test");

        var response = loginService().login(
                new LoginRequest(
                        IdentifierType.HANDLE,
                        "owner",
                        null,
                        "Pass1234"),
                new ClientInfo("127.0.0.1", "test"));

        assertThat(response.token().refreshToken()).isEqualTo("refresh");
    }

    @Test
    void loginCapturesEpochBeforeReloadingAndValidatingCredentials() {
        User discovered = User.builder().id(7L).handle("owner").build();
        User current = User.builder()
                .id(7L)
                .handle("owner")
                .passwordHash("encoded-current")
                .build();
        when(userService.findByHandle("owner"))
                .thenReturn(Optional.of(discovered));
        when(refreshTokenStore.captureEpoch(7L)).thenReturn(4L);
        when(userService.findById(7L)).thenReturn(Optional.of(current));
        when(passwordEncoder.matches("Pass1234", "encoded-current"))
                .thenReturn(true);
        when(jwtService.issueTokenPair(current)).thenReturn(tokenPair("new-jti"));
        when(refreshTokenStore.storeTokenIfEpochMatches(
                eq(7L), eq("new-jti"), any(), eq(4L)))
                .thenReturn(true);

        loginService().login(
                new LoginRequest(
                        IdentifierType.HANDLE,
                        "owner",
                        null,
                        "Pass1234"),
                new ClientInfo("127.0.0.1", "test"));

        InOrder order = inOrder(
                userService,
                refreshTokenStore,
                passwordEncoder,
                jwtService,
                loginFailureGuard);
        order.verify(userService).findByHandle("owner");
        order.verify(refreshTokenStore).captureEpoch(7L);
        order.verify(userService).findById(7L);
        order.verify(passwordEncoder)
                .matches("Pass1234", "encoded-current");
        order.verify(jwtService).issueTokenPair(current);
        order.verify(refreshTokenStore).storeTokenIfEpochMatches(
                eq(7L), eq("new-jti"), any(), eq(4L));
        order.verify(loginFailureGuard).onSuccess("owner");
    }

    @Test
    void loginCannotIssueIntoAnEpochAdvancedDuringAuthentication() {
        User discovered = User.builder().id(7L).handle("owner").build();
        User current = User.builder()
                .id(7L)
                .handle("owner")
                .passwordHash("encoded-old")
                .build();
        when(userService.findByHandle("owner"))
                .thenReturn(Optional.of(discovered));
        when(refreshTokenStore.captureEpoch(7L)).thenReturn(8L);
        when(userService.findById(7L)).thenReturn(Optional.of(current));
        when(passwordEncoder.matches("OldPass123", "encoded-old"))
                .thenReturn(true);
        when(jwtService.issueTokenPair(current)).thenReturn(tokenPair("stale-jti"));
        when(refreshTokenStore.storeTokenIfEpochMatches(
                eq(7L), eq("stale-jti"), any(), eq(8L)))
                .thenReturn(false);

        assertThatThrownBy(() -> loginService().login(
                new LoginRequest(
                        IdentifierType.HANDLE,
                        "owner",
                        null,
                        "OldPass123"),
                new ClientInfo("127.0.0.1", "test")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        verify(loginFailureGuard, never()).onSuccess("owner");
        verify(loginLogService, never()).recordSuccess(
                7L, "owner", "PASSWORD", "127.0.0.1", "test");
    }

    @Test
    void sendCodeRejectsRegisterForExistingAndLoginForMissingIdentifiers() {
        AuthVerificationCodeService service =
                new AuthVerificationCodeService(
                        identityPolicy, verificationService);
        when(userService.existsByEmail("existing@example.com"))
                .thenReturn(true);
        when(userService.existsByEmail("missing@example.com"))
                .thenReturn(false);

        assertThatThrownBy(() -> service.sendCode(new SendCodeRequest(
                VerificationScene.REGISTER,
                IdentifierType.EMAIL,
                "existing@example.com")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.IDENTIFIER_EXISTS);
        assertThatThrownBy(() -> service.sendCode(new SendCodeRequest(
                VerificationScene.LOGIN,
                IdentifierType.EMAIL,
                "missing@example.com")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.IDENTIFIER_NOT_FOUND);

        verify(verificationService, never())
                .sendCode(any(), anyString());
    }

    @Test
    void registrationTermsAndVerificationFailuresDoNotCreateAUser() {
        AuthRegistrationService service = registrationService();

        assertThatThrownBy(() -> service.register(
                new RegisterRequest(
                        IdentifierType.HANDLE,
                        null,
                        "owner",
                        null,
                        "Pass1234",
                        "Owner",
                        false),
                new ClientInfo("127.0.0.1", "test")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.TERMS_NOT_ACCEPTED);

        when(userService.existsByEmail("owner@example.com"))
                .thenReturn(false);
        when(verificationService.verify(
                VerificationScene.REGISTER,
                "owner@example.com",
                "123456"))
                .thenReturn(new VerificationCheckResult(
                        VerificationCodeStatus.EXPIRED, 0, 5));
        assertThatThrownBy(() -> service.register(
                new RegisterRequest(
                        IdentifierType.EMAIL,
                        "owner@example.com",
                        null,
                        "123456",
                        null,
                        null,
                        true),
                new ClientInfo("127.0.0.1", "test")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.VERIFICATION_NOT_FOUND);

        verify(userService, never()).createUser(any(User.class));
    }

    @Test
    void missingCurrentUserUsesTheStableIdentifierNotFoundError() {
        when(userService.findById(7L)).thenReturn(Optional.empty());
        AuthCurrentUserService service = new AuthCurrentUserService(
                identityPolicy);

        assertThatThrownBy(() -> service.me(7L))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.IDENTIFIER_NOT_FOUND);
    }

    @Test
    void resetPasswordAtomicallyStoresTheNewHashAndAdvancesTheSessionEpoch() {
        User user = User.builder().id(9L).email("owner@example.com").build();
        when(userService.findByEmail("owner@example.com"))
                .thenReturn(Optional.of(user));
        when(verificationService.verify(
                VerificationScene.RESET_PASSWORD,
                "owner@example.com",
                "123456"))
                .thenReturn(new VerificationCheckResult(
                        VerificationCodeStatus.SUCCESS, 0, 5));
        when(passwordEncoder.encode(" Next1234 ")).thenReturn("encoded-next");

        passwordRecoveryService.resetPassword(new PasswordResetRequest(
                IdentifierType.EMAIL,
                "OWNER@EXAMPLE.COM",
                "123456",
                " Next1234 "));

        InOrder order = inOrder(
                verificationService,
                passwordEncoder,
                userService);
        order.verify(verificationService).verify(
                VerificationScene.RESET_PASSWORD,
                "owner@example.com",
                "123456");
        order.verify(passwordEncoder).encode(" Next1234 ");
        order.verify(userService).updatePasswordAndAdvanceRefreshSessionEpoch(
                9L, "encoded-next");
        verifyNoInteractions(refreshTokenStore);
        verify(jwtService, never()).issueTokenPair(user);
    }

    @Test
    void resetPasswordPropagatesTheAtomicCredentialUpdateFailure() {
        User user = User.builder()
                .id(9L)
                .email("owner@example.com")
                .passwordHash("encoded-old")
                .build();
        when(userService.findByEmail("owner@example.com"))
                .thenReturn(Optional.of(user));
        when(verificationService.verify(
                VerificationScene.RESET_PASSWORD,
                "owner@example.com",
                "123456"))
                .thenReturn(new VerificationCheckResult(
                        VerificationCodeStatus.SUCCESS, 0, 5));
        when(passwordEncoder.encode("Next1234")).thenReturn("encoded-next");
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(userService)
                .updatePasswordAndAdvanceRefreshSessionEpoch(
                        9L, "encoded-next");

        assertThatThrownBy(() -> passwordRecoveryService.resetPassword(
                new PasswordResetRequest(
                        IdentifierType.EMAIL,
                        "owner@example.com",
                        "123456",
                        "Next1234")))
                .isInstanceOf(IllegalStateException.class);

        verify(userService).updatePasswordAndAdvanceRefreshSessionEpoch(
                9L, "encoded-next");
        verifyNoInteractions(refreshTokenStore);
    }

    private static Jwt jwt(String type, long userId, String tokenId) {
        Instant now = Instant.now();
        return new Jwt(
                "raw",
                now,
                now.plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("token_type", type, "uid", userId, "jti", tokenId));
    }

    private static TokenPair tokenPair(String tokenId) {
        Instant now = Instant.now();
        return new TokenPair(
                "access",
                now.plusSeconds(900),
                "refresh",
                now.plusSeconds(3600),
                tokenId);
    }

    private AuthLoginService loginService() {
        return new AuthLoginService(
                verificationService,
                passwordEncoder,
                identityPolicy,
                tokenLifecycleService,
                loginLogService,
                loginFailureGuard,
                userBanService);
    }

    private AuthRegistrationService registrationService() {
        return new AuthRegistrationService(
                userService,
                verificationService,
                passwordEncoder,
                identityPolicy,
                tokenLifecycleService,
                new AuthRegistrationSideEffectCoordinator(
                        loginLogService, eventPublisher));
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static void runAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    private static void runAfterCompletion(int status) {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization ->
                        synchronization.afterCompletion(status));
    }
}
