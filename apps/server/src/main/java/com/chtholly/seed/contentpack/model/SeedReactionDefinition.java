package com.chtholly.seed.contentpack.model;

/**
 * Immutable post reaction input loaded from a content pack.
 */
public record SeedReactionDefinition(
        String seedKey,
        String postSeedKey,
        String accountSeedKey,
        String type) {
}
