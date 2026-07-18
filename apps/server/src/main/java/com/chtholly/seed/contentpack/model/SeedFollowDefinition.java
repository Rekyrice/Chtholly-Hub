package com.chtholly.seed.contentpack.model;

import java.time.Instant;

/**
 * Immutable account-follow input loaded from a content pack.
 */
public record SeedFollowDefinition(
        String seedKey,
        String fromAccountSeedKey,
        String toAccountSeedKey,
        String toHandle,
        Instant createdAt) {

    /** Creates the original Seed-to-Seed follow shape. */
    public SeedFollowDefinition(
            String seedKey,
            String fromAccountSeedKey,
            String toAccountSeedKey,
            Instant createdAt) {
        this(seedKey, fromAccountSeedKey, toAccountSeedKey, null, createdAt);
    }
}
