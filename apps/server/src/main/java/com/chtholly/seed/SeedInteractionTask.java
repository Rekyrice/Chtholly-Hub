package com.chtholly.seed;

import java.time.Instant;
import java.util.List;

/**
 * Delayed seed interaction job stored in Redis.
 */
public record SeedInteractionTask(
        String id,
        long postId,
        long authorUserId,
        String postTitle,
        List<String> postTags,
        String postExcerpt,
        int round,
        Long parentCommentId,
        String parentCommentContent,
        List<SeedInteractionAccount> candidates,
        Instant scheduledAt
) {
}
