package com.chtholly.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** JWT 令牌对。 */
@Schema(description = "Token 响应")
public record TokenResponse(
        @Schema(description = "Access Token") String accessToken,
        @Schema(description = "Access Token 过期时间") Instant accessTokenExpiresAt,
        @Schema(description = "Refresh Token") String refreshToken,
        @Schema(description = "Refresh Token 过期时间") Instant refreshTokenExpiresAt
) {
}
