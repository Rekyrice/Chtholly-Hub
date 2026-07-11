package com.chtholly.seed.contentpack.model;

/**
 * Immutable minimum view-count input loaded from a content pack.
 */
public record SeedViewDefinition(
        String seedKey,
        String postSeedKey,
        long minimumCount) {
}
