package com.chtholly.user.api.dto;

/** 公开用户资料（个人主页用，不含手机号等敏感字段）。 */
public record PublicUserResponse(
        String id,
        String handle,
        String nickname,
        String avatar,
        String bio,
        long publicPostCount
) {}
