package com.chtholly.storage;

import java.util.Map;

/**
 * 直传 URL 信息：OSS 使用 PUT 预签名，本地使用 POST multipart。
 */
public record PresignedUrl(
        String url,
        Map<String, String> headers,
        int expiresInSeconds,
        String method
) {}
