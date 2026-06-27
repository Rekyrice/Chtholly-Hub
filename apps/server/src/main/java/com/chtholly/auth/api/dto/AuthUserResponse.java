package com.chtholly.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/** 当前登录用户基本信息。 */
@Schema(description = "认证用户信息")
public record AuthUserResponse(
        @Schema(description = "用户 ID") Long id,
        @Schema(description = "昵称") String nickname,
        @Schema(description = "头像 URL") String avatar,
        @Schema(description = "手机号") String phone,
        @Schema(description = "站内 handle") String zhId,
        @Schema(description = "生日") LocalDate birthday,
        @Schema(description = "学校") String school,
        @Schema(description = "简介") String bio,
        @Schema(description = "性别") String gender,
        @Schema(description = "标签 JSON") String tagJson
) {
}
