package com.chtholly.seed;

import java.util.List;

/**
 * Context visible to a seed account when generating a reply.
 */
public record SeedInteractionContext(
        long postId,
        String postTitle,
        List<String> postTags,
        String postExcerpt,
        Long parentCommentId,
        String parentCommentContent,
        int round
) {
}
