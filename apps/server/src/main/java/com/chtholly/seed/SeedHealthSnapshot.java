package com.chtholly.seed;

import java.time.Instant;

/**
 * Seed account health snapshot for daily monitoring.
 */
public record SeedHealthSnapshot(
        long userId,
        String handle,
        String nickname,
        long posts7d,
        long comments7d,
        double averageQualityScore,
        Instant monitoredAt
) {
}
