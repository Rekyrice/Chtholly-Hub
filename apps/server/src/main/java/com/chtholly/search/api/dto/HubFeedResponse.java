package com.chtholly.search.api.dto;

import com.chtholly.agent.api.dto.AgentExperienceResponse;
import com.chtholly.post.api.dto.FeedItemResponse;

import java.util.List;

/**
 * Aggregated Hub feed payload returned by one Elasticsearch msearch request.
 *
 * @param latestPosts latest published posts
 * @param latestPostsStatus latest posts status, {@code ok} or {@code degraded}
 * @param latestPostsTotal total published posts matching the latest feed query
 * @param hotTags hot tags aggregated from post documents
 * @param hotTagsStatus hot tags status, {@code ok} or {@code degraded}
 * @param recommendations personalized or fallback recommendations
 * @param recommendationsStatus recommendations status, {@code ok} or {@code degraded}
 * @param experiences recent Chtholly experiences from the experience index
 * @param experiencesStatus experiences status, {@code ok} or {@code degraded}
 */
public record HubFeedResponse(
        List<FeedItemResponse> latestPosts,
        String latestPostsStatus,
        int latestPostsTotal,
        List<TagCountResponse> hotTags,
        String hotTagsStatus,
        List<FeedItemResponse> recommendations,
        String recommendationsStatus,
        List<AgentExperienceResponse> experiences,
        String experiencesStatus
) {
}
