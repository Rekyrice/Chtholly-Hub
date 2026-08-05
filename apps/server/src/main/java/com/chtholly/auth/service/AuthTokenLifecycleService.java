package com.chtholly.auth.service;

import com.chtholly.admin.security.UserBanService;
import com.chtholly.auth.api.dto.TokenRefreshRequest;
import com.chtholly.auth.api.dto.TokenResponse;
import com.chtholly.auth.token.JwtService;
import com.chtholly.auth.token.PendingUserRefreshTokenStore;
import com.chtholly.auth.token.RefreshTokenStore;
import com.chtholly.auth.token.TokenPair;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.user.domain.User;
import com.chtholly.user.service.UserService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Coordinates refresh-token issuance, rotation, revocation, and logout. */
@Service
public class AuthTokenLifecycleService {

    private static final Logger log =
            LoggerFactory.getLogger(AuthTokenLifecycleService.class);

    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;
    private final PendingUserRefreshTokenStore pendingUserRefreshTokenStore;
    private final UserService userService;
    private final UserBanService userBanService;

    /**
     * Creates the token lifecycle use case.
     *
     * @param jwtService JWT codec and issuer
     * @param refreshTokenStore refresh-token whitelist
     * @param pendingUserRefreshTokenStore pending-registration membership port
     * @param userService user lookup service
     * @param userBanService user-ban policy
     */
    public AuthTokenLifecycleService(
            JwtService jwtService,
            RefreshTokenStore refreshTokenStore,
            PendingUserRefreshTokenStore pendingUserRefreshTokenStore,
            UserService userService,
            UserBanService userBanService) {
        this.jwtService = jwtService;
        this.refreshTokenStore = refreshTokenStore;
        this.pendingUserRefreshTokenStore = pendingUserRefreshTokenStore;
        this.userService = userService;
        this.userBanService = userBanService;
    }

    /** Issues and whitelists a token pair for a successful authentication. */
    public TokenResponse issue(User user) {
        TokenPair tokenPair = jwtService.issueTokenPair(user);
        storeRefreshToken(user.getId(), tokenPair);
        return AuthResponseMapper.toToken(tokenPair);
    }

    /**
     * Issues the initial token pair for a user not committed by registration.
     *
     * <p>A rollback or unknown completion status triggers best-effort removal
     * of the Redis membership. The storage adapter rechecks committed MySQL
     * state before deleting, so an uncertain successful commit remains safe.</p>
     *
     * @param user pending registration user
     * @return issued token response
     */
    public TokenResponse issueForPendingRegistration(User user) {
        requireRegistrationTransaction();
        long userId = requirePendingUserId(user);
        TokenPair tokenPair = jwtService.issueTokenPair(user);
        registerPendingMembershipCompensation(
                userId, tokenPair.refreshTokenId());
        pendingUserRefreshTokenStore.storeInitialTokenForPendingUser(
                userId,
                tokenPair.refreshTokenId(),
                refreshTokenTtl(tokenPair));
        return AuthResponseMapper.toToken(tokenPair);
    }

    /** Captures the session epoch before authentication reads credentials. */
    public long captureIssueEpoch(long userId) {
        return refreshTokenStore.captureEpoch(userId);
    }

    /**
     * Issues a token pair only while the pre-authentication epoch is current.
     */
    public TokenResponse issueAtEpoch(User user, long expectedEpoch) {
        TokenPair tokenPair = jwtService.issueTokenPair(user);
        boolean stored = refreshTokenStore.storeTokenIfEpochMatches(
                user.getId(),
                tokenPair.refreshTokenId(),
                refreshTokenTtl(tokenPair),
                expectedEpoch);
        if (!stored) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        return AuthResponseMapper.toToken(tokenPair);
    }

    /** Rotates one valid refresh token with one-time consumption semantics. */
    public TokenResponse refresh(TokenRefreshRequest request) {
        Jwt jwt = decodeRefreshToken(request.refreshToken());
        if (!Objects.equals("refresh", jwtService.extractTokenType(jwt))) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        long userId = jwtService.extractUserId(jwt);
        String tokenId = jwtService.extractTokenId(jwt);
        if (!refreshTokenStore.isTokenValid(userId, tokenId)) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        User user = userService.findById(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));
        if (user.getBannedAt() != null) {
            refreshTokenStore.revokeToken(userId, tokenId);
            throw userBanService.bannedException();
        }

        TokenPair tokenPair = jwtService.issueTokenPair(user);
        boolean rotated = refreshTokenStore.rotateToken(
                userId,
                tokenId,
                tokenPair.refreshTokenId(),
                refreshTokenTtl(tokenPair));
        if (!rotated) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        return AuthResponseMapper.toToken(tokenPair);
    }

    /** Revokes a syntactically valid refresh token and ignores malformed input. */
    public void logout(String refreshToken) {
        decodeRefreshTokenSafely(refreshToken).ifPresent(jwt -> {
            if (Objects.equals("refresh", jwtService.extractTokenType(jwt))) {
                long userId = jwtService.extractUserId(jwt);
                String tokenId = jwtService.extractTokenId(jwt);
                refreshTokenStore.revokeToken(userId, tokenId);
            }
        });
    }

    /** Revokes every refresh token owned by one user. */
    public void revokeAll(long userId) {
        refreshTokenStore.revokeAll(userId);
    }

    private void storeRefreshToken(long userId, TokenPair tokenPair) {
        refreshTokenStore.storeToken(
                userId,
                tokenPair.refreshTokenId(),
                refreshTokenTtl(tokenPair));
    }

    private Duration refreshTokenTtl(TokenPair tokenPair) {
        Duration ttl = Duration.between(
                Instant.now(), tokenPair.refreshTokenExpiresAt());
        return ttl.isNegative() ? Duration.ZERO : ttl;
    }

    private void registerPendingMembershipCompensation(
            long userId,
            String tokenId) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_COMMITTED) {
                            return;
                        }
                        try {
                            pendingUserRefreshTokenStore
                                    .discardInitialTokenForPendingUser(
                                            userId, tokenId);
                        } catch (Exception failure) {
                            log.warn(
                                    "Pending registration token cleanup failed, userId={}, errorType={}",
                                    userId,
                                    failure.getClass().getSimpleName());
                        }
                    }
                });
    }

    private static void requireRegistrationTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager
                        .isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Pending token issuance requires an active registration transaction");
        }
    }

    private static long requirePendingUserId(User user) {
        if (user == null || user.getId() == null || user.getId() < 1L) {
            throw new IllegalStateException(
                    "Pending registration user ID is unavailable");
        }
        return user.getId();
    }

    private Jwt decodeRefreshToken(String refreshToken) {
        try {
            return jwtService.decode(refreshToken);
        } catch (JwtException ex) {
            log.warn(
                    "JWT decode rejected, operation=refresh, errorType={}",
                    ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
    }

    private Optional<Jwt> decodeRefreshTokenSafely(String refreshToken) {
        try {
            return Optional.of(jwtService.decode(refreshToken));
        } catch (JwtException ex) {
            log.warn(
                    "JWT decode rejected, operation=logout, errorType={}",
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
