package com.chtholly.relation.util;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * 关注/粉丝列表游标：createdAtMillis + userId 的 Base64URL 表示。
 */
public final class RelationCursor {

    private RelationCursor() {
    }

    public static String encode(long createdAtMillis, long userId) {
        String raw = createdAtMillis + ":" + userId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Optional<RelationCursorPoint> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        String trimmed = cursor.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            try {
                return Optional.of(new RelationCursorPoint(Long.parseLong(trimmed), null));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(trimmed), StandardCharsets.UTF_8);
            int sep = decoded.lastIndexOf(':');
            if (sep <= 0 || sep >= decoded.length() - 1) {
                return Optional.empty();
            }
            long millis = Long.parseLong(decoded.substring(0, sep));
            long userId = Long.parseLong(decoded.substring(sep + 1));
            return Optional.of(new RelationCursorPoint(millis, userId));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static RelationCursorPoint require(String cursor) {
        return decode(cursor).orElseThrow(() ->
                new BusinessException(ErrorCode.BAD_REQUEST, "关系列表游标非法"));
    }

    public record RelationCursorPoint(long createdAtMillis, Long userId) {
    }
}
