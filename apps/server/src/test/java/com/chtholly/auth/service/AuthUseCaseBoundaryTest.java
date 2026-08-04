package com.chtholly.auth.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chtholly.admin.security.UserBanService;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
                jwtService, refreshTokenStore, userService, userBanService);
        passwordRecoveryService = new AuthPasswordRecoveryService(
                userService,
                verificationService,
                passwordEncoder,
                identityPolicy,
                refreshTokenStore);
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
    void wrongPasswordRecordsAuditAndFailureBeforeRejectingLogin() {
        User user = User.builder()
                .id(7L)
                .handle("owner")
                .passwordHash("encoded")
                .build();
        when(userService.findByHandle("owner"))
                .thenReturn(Optional.of(user));
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
        order.verify(loginLogService).recordFailure(
                7L,
                "owner",
                "PASSWORD",
                "127.0.0.1",
                "test",
                LoginFailureReason.WRONG_PASSWORD);
        order.verify(loginFailureGuard).onFailure("owner", "127.0.0.1");
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
    }

    @Test
    void registrationKeepsTokenAuditAndBestEffortEventOrdering() {
        when(userService.existsByHandle("owner")).thenReturn(false);
        when(passwordEncoder.encode("Pass1234")).thenReturn("encoded");
        when(userService.createUser(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(7L);
            return user;
        });
        when(jwtService.issueTokenPair(any(User.class)))
                .thenReturn(tokenPair("new-jti"));
        doThrow(new IllegalStateException("listener down"))
                .when(eventPublisher)
                .publishEvent(any(Object.class));
        AuthRegistrationService service = new AuthRegistrationService(
                userService,
                verificationService,
                passwordEncoder,
                identityPolicy,
                tokenLifecycleService,
                loginLogService,
                eventPublisher);

        var response = service.register(
                new RegisterRequest(
                        IdentifierType.HANDLE,
                        null,
                        "owner",
                        null,
                        "Pass1234",
                        "Owner",
                        true),
                new com.chtholly.auth.model.ClientInfo("127.0.0.1", "test"));

        InOrder order = inOrder(
                userService,
                jwtService,
                refreshTokenStore,
                loginLogService,
                eventPublisher);
        order.verify(userService).createUser(any(User.class));
        order.verify(jwtService).issueTokenPair(any(User.class));
        order.verify(refreshTokenStore).storeToken(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("new-jti"),
                org.mockito.ArgumentMatchers.any());
        order.verify(loginLogService).recordSuccess(
                7L, "owner", "REGISTER", "127.0.0.1", "test");
        order.verify(eventPublisher).publishEvent(any(Object.class));
        assertThat(response.user().id()).isEqualTo(7L);
        assertThat(response.token().refreshToken()).isEqualTo("refresh");
    }

    @Test
    void refreshRotatesWhitelistOnlyAfterIssuingReplacementPair() {
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

        var response = tokenLifecycleService.refresh(
                new TokenRefreshRequest("old-refresh"));

        InOrder order = inOrder(jwtService, refreshTokenStore);
        order.verify(jwtService).issueTokenPair(user);
        order.verify(refreshTokenStore).revokeToken(7L, "old-jti");
        order.verify(refreshTokenStore).storeToken(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("new-jti"),
                org.mockito.ArgumentMatchers.any());
        assertThat(response.refreshToken()).isEqualTo("refresh");
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
    void verificationCodeFailureIsAuditedAndCountedByTheLoginGuard() {
        User user = User.builder().id(7L).email("owner@example.com").build();
        when(userService.findByEmail("owner@example.com"))
                .thenReturn(Optional.of(user));
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
                .isEqualTo(ErrorCode.VERIFICATION_MISMATCH);

        InOrder order = inOrder(loginLogService, loginFailureGuard);
        order.verify(loginLogService).recordFailure(
                7L,
                "owner@example.com",
                "CODE",
                "127.0.0.1",
                "test",
                null);
        order.verify(loginFailureGuard)
                .onFailure("owner@example.com", "127.0.0.1");
    }

    @Test
    void missingPasswordAccountCountsAsFailureButMissingCodeAccountDoesNot() {
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
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.login(
                new LoginRequest(
                        IdentifierType.HANDLE,
                        "missing",
                        "123456",
                        null),
                new ClientInfo("127.0.0.1", "test")))
                .isInstanceOf(BusinessException.class);

        verify(loginFailureGuard).onFailure("missing", "127.0.0.1");
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
    void resetPasswordVerifiesBeforeUpdatingAndRevokesEveryRefreshToken() {
        User user = User.builder().id(9L).email("owner@example.com").build();
        when(userService.findByEmail("owner@example.com"))
                .thenReturn(Optional.of(user));
        when(verificationService.verify(
                VerificationScene.RESET_PASSWORD,
                "owner@example.com",
                "123456"))
                .thenReturn(new VerificationCheckResult(
                        VerificationCodeStatus.SUCCESS, 0, 5));
        when(passwordEncoder.encode("Next1234")).thenReturn("encoded-next");

        passwordRecoveryService.resetPassword(new PasswordResetRequest(
                IdentifierType.EMAIL,
                "OWNER@EXAMPLE.COM",
                "123456",
                "Next1234"));

        InOrder order = inOrder(
                verificationService,
                passwordEncoder,
                userService,
                refreshTokenStore);
        order.verify(verificationService).verify(
                VerificationScene.RESET_PASSWORD,
                "owner@example.com",
                "123456");
        order.verify(passwordEncoder).encode("Next1234");
        order.verify(userService).updatePassword(user);
        order.verify(refreshTokenStore).revokeAll(9L);
        assertThat(user.getPasswordHash()).isEqualTo("encoded-next");
        verify(jwtService, never()).issueTokenPair(user);
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
                loginLogService,
                eventPublisher);
    }
}
