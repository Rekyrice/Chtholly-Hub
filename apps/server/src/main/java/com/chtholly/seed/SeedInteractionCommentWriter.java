package com.chtholly.seed;

import java.time.Instant;

/**
 * Writes generated seed comments.
 */
public interface SeedInteractionCommentWriter {

    long writeComment(long postId, Long parentCommentId, long userId, String content, Instant createdAt);
}
