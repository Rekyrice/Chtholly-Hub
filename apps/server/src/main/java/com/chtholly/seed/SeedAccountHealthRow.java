package com.chtholly.seed;

/**
 * Aggregated seed account activity loaded from MySQL.
 */
public record SeedAccountHealthRow(
        long userId,
        String handle,
        String nickname,
        long posts7d,
        long comments7d
) {
}
