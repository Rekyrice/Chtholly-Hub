package com.chtholly.seed.contentpack.model;

/**
 * Immutable post reaction input loaded from a content pack.
 */
public record SeedReactionDefinition(
        String seedKey,
        String postSeedKey,
        String postSlug,
        String accountSeedKey,
        String type) {

    /** Creates a reaction targeting a post declared inside a legacy pack. */
    public SeedReactionDefinition(String seedKey, String postSeedKey, String accountSeedKey, String type) {
        this(seedKey, postSeedKey, null, accountSeedKey, type);
    }
}
