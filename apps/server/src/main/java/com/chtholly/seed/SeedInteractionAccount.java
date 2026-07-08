package com.chtholly.seed;

/**
 * Seed persona bound to the persisted user id.
 */
public record SeedInteractionAccount(
        long userId,
        SeedAccountProfile profile
) {
}
