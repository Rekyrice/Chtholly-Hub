package com.chtholly.auth.service;

import com.chtholly.admin.security.UserBanService;
import com.chtholly.auth.api.dto.TokenRefreshRequest;
import com.chtholly.auth.api.dto.TokenResponse;
import com.chtholly.auth.token.JwtService;
import com.chtholly.auth.token.RefreshTokenStore;
import com.chtholly.auth.token.TokenPair;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.user.domain.User;
import com.chtholly.user.service.UserService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
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
    private final UserService userService;
    private final UserBanService userBanService;

    /**
     * Creates the token lifecycle use case.
     *
     * @param jwtService JWT codec and issuer
     * @param refreshTokenStore refresh-token whitelist
     * @param userService user lookup service
     * @param userBanService user-ban policy
     */
    public AuthTokenLifecycleService(
            JwtService jwtService,
            RefreshTokenStore refreshTokenStore,
            UserService userService,
            UserBanService userBanService) {
        this.jwtService = jwtService;
        this.refreshTokenStore = refreshTokenStore;
        this.userService = userService;
        this.userBanService = userBanService;
    }

    /** Issues and whitelists a token pair for a successful authentication. */
    public TokenResponse issue(User user) {
        TokenPair tokenPair = jwtService.issueTokenPair(user);
        storeRefreshToken(user.getId(), tokenPair);
        return AuthResponseMapper.toToken(tokenPair);
    }

    /** Rotates one valid refresh token while preserving the original ordering. */
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
                        new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND));
        if (user.getBannedAt() != null) {
            refreshTokenStore.revokeToken(userId, tokenId);
            throw userBanService.bannedException();
        }

        TokenPair tokenPair = jwtService.issueTokenPair(user);
        refreshTokenStore.revokeToken(userId, tokenId);
        storeRefreshToken(userId, tokenPair);
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
        Duration ttl = Duration.between(
                Instant.now(), tokenPair.refreshTokenExpiresAt());
        if (ttl.isNegative()) {
            ttl = Duration.ZERO;
        }
        refreshTokenStore.storeToken(
                userId, tokenPair.refreshTokenId(), ttl);
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
