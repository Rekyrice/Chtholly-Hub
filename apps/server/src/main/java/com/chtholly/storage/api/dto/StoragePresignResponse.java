package com.chtholly.storage.api.dto;

import java.util.Map;

/**
 * Backward-compatible HTTP response describing an upload contract.
 */
public record StoragePresignResponse(
        String objectKey,
        String putUrl,
        Map<String, String> headers,
        int expiresIn,
        String method,
        String publicUrl
) {}
