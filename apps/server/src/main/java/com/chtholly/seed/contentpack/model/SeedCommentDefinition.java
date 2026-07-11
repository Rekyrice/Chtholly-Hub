package com.chtholly.seed.contentpack.model;

import java.time.Instant;

/**
 * Immutable comment input loaded from a content pack.
 */
public record SeedCommentDefinition(
        String seedKey,
        Integer legacyOrdinal,
        String postSeedKey,
        String authorSeedKey,
        String parentSeedKey,
        String content,
        Instant createdAt) {
}
