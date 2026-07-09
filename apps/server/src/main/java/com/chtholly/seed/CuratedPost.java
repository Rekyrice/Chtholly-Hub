package com.chtholly.seed;

/**
 * A post selected for Chtholly's weekly collection.
 */
public record CuratedPost(
        long postId,
        String title,
        String chthollyComment
) {
}
