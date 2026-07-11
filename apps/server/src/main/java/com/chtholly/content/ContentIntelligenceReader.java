package com.chtholly.content;

import java.util.List;

/**
 * Read-only boundary for content intelligence results consumed by application modules.
 */
public interface ContentIntelligenceReader {

    /**
     * Returns stored content analysis for a post.
     *
     * @param postId post ID
     * @return analysis or null
     */
    ContentAnalysis getAnalysis(Long postId);

    /**
     * Returns stored content analysis for a public post slug.
     *
     * @param slug post URL slug
     * @return analysis or null
     */
    ContentAnalysis getAnalysisBySlug(String slug);

    /**
     * Returns related posts based on content intelligence.
     *
     * @param postId source post ID
     * @return related post list
     */
    List<RelatedPostDto> getRelatedPosts(Long postId);
}
