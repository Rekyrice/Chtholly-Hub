package com.chtholly.auth.api.dto;

import com.chtholly.auth.model.IdentifierType;
import com.chtholly.auth.verification.VerificationScene;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 发送验证码请求。 */
@Schema(description = "发送验证码请求")
public record SendCodeRequest(
        @Schema(description = "场景：REGISTER / LOGIN / RESET_PASSWORD") @NotNull(message = "场景不能为空") VerificationScene scene,
        @Schema(description = "账号类型") @NotNull(message = "账号类型不能为空") IdentifierType identifierType,
        @Schema(description = "手机号或邮箱") @NotBlank(message = "账号不能为空") String identifier
) {
}
