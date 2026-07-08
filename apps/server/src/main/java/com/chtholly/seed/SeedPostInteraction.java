package com.chtholly.seed;

import java.util.List;

/**
 * Post context used to start a multi-round seed discussion.
 */
public record SeedPostInteraction(
        long postId,
        long authorUserId,
        String postTitle,
        List<String> tags,
        String excerpt
) {
}
