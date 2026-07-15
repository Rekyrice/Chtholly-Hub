package com.chtholly.seed.contentpack.model;

import java.time.Instant;

/**
 * Immutable media asset input loaded from a content pack.
 */
public record SeedAssetDefinition(
        String key,
        String source,
        String sourceUrl,
        String sourcePageUrl,
        Instant fetchedAt,
        String usageNote,
        String sourceFile,
        String file,
        String objectKey,
        String sha256,
        String contentType,
        int width,
        int height,
        String usage) {
}
