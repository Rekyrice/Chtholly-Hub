package com.chtholly.post.util;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * 公开 Feed 游标编解码：publishTime + postId 的 Base64URL 表示。
 */
public final class FeedCursor {

    private FeedCursor() {
    }

    /**
     * 将排序锚点编码为游标字符串。
     */
    public static String encode(Instant publishTime, long postId) {
        if (publishTime == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "游标编码缺少 publishTime");
        }
        String raw = publishTime.toEpochMilli() + ":" + postId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析游标；非法输入返回 empty。
     */
    public static Optional<FeedCursorPoint> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = decoded.lastIndexOf(':');
            if (sep <= 0 || sep >= decoded.length() - 1) {
                return Optional.empty();
            }
            long epochMillis = Long.parseLong(decoded.substring(0, sep));
            long postId = Long.parseLong(decoded.substring(sep + 1));
            return Optional.of(new FeedCursorPoint(Instant.ofEpochMilli(epochMillis), postId));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 解析游标，失败时抛出业务异常。
     */
    public static FeedCursorPoint require(String cursor) {
        return decode(cursor).orElseThrow(() ->
                new BusinessException(ErrorCode.BAD_REQUEST, "Feed 游标非法"));
    }

    /** Redis 缓存槽位：首页用 head，否则用游标原值。 */
    public static String cacheSlot(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return "head";
        }
        return cursor;
    }

    public record FeedCursorPoint(Instant publishTime, long postId) {
    }
}
