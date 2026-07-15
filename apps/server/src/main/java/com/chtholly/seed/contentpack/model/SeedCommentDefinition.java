package com.chtholly.seed.contentpack.model;

import java.time.Instant;

/**
 * Immutable comment input loaded from a content pack.
 */
public record SeedCommentDefinition(
        String seedKey,
        Integer legacyOrdinal,
        String postSeedKey,
        String postSlug,
        String authorSeedKey,
        String parentSeedKey,
        String content,
        Instant createdAt) {

    /** Creates a comment targeting a post declared inside a legacy pack. */
    public SeedCommentDefinition(
            String seedKey,
            Integer legacyOrdinal,
            String postSeedKey,
            String authorSeedKey,
            String parentSeedKey,
            String content,
            Instant createdAt) {
        this(seedKey, legacyOrdinal, postSeedKey, null, authorSeedKey, parentSeedKey, content, createdAt);
    }
}
