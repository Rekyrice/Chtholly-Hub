package com.chtholly.admin.role;

/**
 * 用户角色枚举；MVP 阶段 Admin API 主要使用 ADMIN。
 */
public enum Role {
    USER,
    MODERATOR,
    ADMIN;

    public static Role fromString(String value) {
        if (value == null || value.isBlank()) {
            return USER;
        }
        try {
            return Role.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return USER;
        }
    }
}
