package com.chtholly.auth.api.dto;

import com.chtholly.auth.model.IdentifierType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 注册请求。 */
@Schema(description = "注册请求")
public record RegisterRequest(
        @Schema(description = "账号类型") @NotNull(message = "账号类型不能为空") IdentifierType identifierType,
        @Schema(description = "手机号或邮箱") @NotBlank(message = "账号不能为空") String identifier,
        @Schema(description = "验证码") @NotBlank(message = "验证码不能为空") String code,
        @Schema(description = "可选密码") String password,
        @Schema(description = "是否同意服务条款") boolean agreeTerms
) {
}
