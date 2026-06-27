package com.chtholly.common.web;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * HTTP 条件请求与 Cache-Control 辅助工具。
 */
public final class HttpCacheHelper {

    public static final CacheControl PUBLIC_MAX_AGE_60 =
            CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic();

    public static final CacheControl PRIVATE_MAX_AGE_10 =
            CacheControl.maxAge(10, TimeUnit.SECONDS).cachePrivate();

    public static final CacheControl NO_STORE = CacheControl.noStore();

    private HttpCacheHelper() {
    }

    /**
     * 将多个片段拼接后做 SHA-256，取前 16 字节 hex 作为 ETag 值（不含引号）。
     */
    public static String hashEtag(String... parts) {
        String joined = String.join("|", parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 判断 If-None-Match 是否与当前 ETag 匹配。
     */
    public static boolean matchesIfNoneMatch(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank() || etag == null || etag.isBlank()) {
            return false;
        }
        String normalized = stripQuotes(etag);
        for (String candidate : ifNoneMatch.split(",")) {
            String trimmed = stripQuotes(candidate.trim());
            if ("*".equals(trimmed) || trimmed.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static <T> ResponseEntity<T> okPublic(T body, String etag) {
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(PUBLIC_MAX_AGE_60)
                .body(body);
    }

    public static <T> ResponseEntity<T> notModifiedPublic(String etag) {
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .eTag(etag)
                .cacheControl(PUBLIC_MAX_AGE_60)
                .build();
    }

    public static <T> ResponseEntity<T> conditionalPublic(T body, String etag, String ifNoneMatch) {
        if (matchesIfNoneMatch(ifNoneMatch, etag)) {
            return notModifiedPublic(etag);
        }
        return okPublic(body, etag);
    }

    public static <T> ResponseEntity<T> okPrivate(T body) {
        return ResponseEntity.ok()
                .cacheControl(PRIVATE_MAX_AGE_10)
                .body(body);
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.startsWith("W/")) {
            return stripQuotes(trimmed.substring(2));
        }
        return trimmed;
    }
}
