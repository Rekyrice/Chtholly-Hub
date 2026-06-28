package com.chtholly.auth.api.dto;

import com.chtholly.auth.model.IdentifierType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 登录请求（验证码或密码二选一）。 */
@Schema(description = "登录请求")
public record LoginRequest(
        @Schema(description = "账号类型：PHONE / EMAIL / HANDLE") @NotNull(message = "账号类型不能为空") IdentifierType identifierType,
        @Schema(description = "手机号或邮箱") @NotBlank(message = "账号不能为空") String identifier,
        @Schema(description = "验证码（验证码登录时填写）") String code,
        @Schema(description = "密码（密码登录时填写）") String password
) {
}
