package com.chtholly.user.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 公开用户资料。 */
@Schema(description = "公开用户资料")
public record PublicUserResponse(
        @Schema(description = "用户 ID") String id,
        @Schema(description = "handle") String handle,
        @Schema(description = "昵称") String nickname,
        @Schema(description = "头像 URL") String avatar,
        @Schema(description = "简介") String bio,
        @Schema(description = "注册时间") Instant createdAt,
        @Schema(description = "公开帖子数") long publicPostCount
) {}
