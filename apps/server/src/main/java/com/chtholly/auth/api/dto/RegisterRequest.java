package com.chtholly.auth.api.dto;

import com.chtholly.auth.model.IdentifierType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 注册请求。 */
@Schema(description = "注册请求")
public record RegisterRequest(
        @Schema(description = "账号类型：PHONE / EMAIL / HANDLE") @NotNull(message = "账号类型不能为空") IdentifierType identifierType,
        @Schema(description = "手机号或邮箱（PHONE/EMAIL 注册时使用）") String identifier,
        @Schema(description = "用户名（HANDLE 注册时使用）") String handle,
        @Schema(description = "验证码（PHONE/EMAIL 注册必填）") String code,
        @Schema(description = "密码（HANDLE 注册必填；PHONE/EMAIL 可选）") String password,
        @Schema(description = "昵称（HANDLE 注册可选）") String nickname,
        @Schema(description = "是否同意服务条款") boolean agreeTerms
) {
}
