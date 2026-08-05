package com.chtholly.storage;

import java.util.Map;

/**
 * Transport contract for an application-mediated object upload.
 */
public record PresignedUrl(
        String url,
        Map<String, String> headers,
        int expiresInSeconds,
        String method
) {}
