package com.chtholly.auth.service;

import com.chtholly.auth.api.dto.AuthUserResponse;
import com.chtholly.auth.api.dto.TokenResponse;
import com.chtholly.auth.token.TokenPair;
import com.chtholly.user.domain.User;

/** Maps authentication domain values to stable API response records. */
final class AuthResponseMapper {

    private AuthResponseMapper() {
    }

    static AuthUserResponse toUser(User user) {
        return new AuthUserResponse(
                user.getId(),
                user.getNickname(),
                user.getAvatar(),
                user.getPhone(),
                user.getHandle(),
                user.getBirthday(),
                user.getSchool(),
                user.getBio(),
                user.getGender(),
                user.getTagsJson(),
                user.getRole());
    }

    static TokenResponse toToken(TokenPair tokenPair) {
        return new TokenResponse(
                tokenPair.accessToken(),
                tokenPair.accessTokenExpiresAt(),
                tokenPair.refreshToken(),
                tokenPair.refreshTokenExpiresAt());
    }
}
