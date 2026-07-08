package com.chtholly.seed;

import java.time.Instant;

/**
 * Seed interaction comment row with optional parent comment.
 */
public record SeedInteractionCommentRow(
        long id,
        long postId,
        Long parentId,
        long userId,
        String content,
        Instant createdAt,
        Instant updatedAt
) {
}
