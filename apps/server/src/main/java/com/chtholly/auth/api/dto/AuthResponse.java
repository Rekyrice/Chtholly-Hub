package com.chtholly.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 认证响应（用户 + Token）。 */
@Schema(description = "认证响应")
public record AuthResponse(
        @Schema(description = "用户信息") AuthUserResponse user,
        @Schema(description = "令牌对") TokenResponse token
) {
}
