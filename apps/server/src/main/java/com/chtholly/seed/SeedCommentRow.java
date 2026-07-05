package com.chtholly.seed;

import java.time.Instant;

/**
 * Comment row inserted by seed scripts.
 */
public record SeedCommentRow(
        long id,
        long postId,
        long userId,
        String content,
        Instant createdAt,
        Instant updatedAt
) {
}
