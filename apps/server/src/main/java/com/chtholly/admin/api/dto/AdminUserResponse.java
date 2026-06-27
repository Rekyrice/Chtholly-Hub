package com.chtholly.admin.api.dto;

import com.chtholly.admin.role.Role;
import com.chtholly.user.domain.User;

import java.time.Instant;

public record AdminUserResponse(
        long id,
        String nickname,
        String phone,
        String email,
        String handle,
        Role role,
        Instant bannedAt,
        Instant createdAt
) {
    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getNickname(),
                user.getPhone(),
                user.getEmail(),
                user.getHandle(),
                Role.fromString(user.getRole()),
                user.getBannedAt(),
                user.getCreatedAt()
        );
    }
}
