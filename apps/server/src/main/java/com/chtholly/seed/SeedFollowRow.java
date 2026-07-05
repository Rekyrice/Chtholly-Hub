package com.chtholly.seed;

import java.time.Instant;

/**
 * Follow relation inserted by seed scripts.
 */
public record SeedFollowRow(
        long id,
        long fromUserId,
        long toUserId,
        Instant createdAt,
        Instant updatedAt
) {
}
