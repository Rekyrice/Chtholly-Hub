package com.chtholly.seed.contentpack.model;

/**
 * Immutable minimum view-count input loaded from a content pack.
 */
public record SeedViewDefinition(
        String seedKey,
        String postSeedKey,
        String postSlug,
        long minimumCount) {

    /** Creates a view baseline targeting a post declared inside a legacy pack. */
    public SeedViewDefinition(String seedKey, String postSeedKey, long minimumCount) {
        this(seedKey, postSeedKey, null, minimumCount);
    }
}
