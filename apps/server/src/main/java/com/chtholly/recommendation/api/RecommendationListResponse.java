package com.chtholly.recommendation.api;

import com.chtholly.recommendation.model.RecommendedPost;

import java.util.List;

/**
 * 个性化推荐 API 响应。
 */
public record RecommendationListResponse(
        List<RecommendedPostItem> items,
        boolean personalized
) {

    public static RecommendationListResponse from(List<RecommendedPost> posts, boolean personalized) {
        List<RecommendedPostItem> items = posts.stream()
                .map(post -> new RecommendedPostItem(
                        post.postId(),
                        post.title(),
                        post.score(),
                        post.reason()))
                .toList();
        return new RecommendationListResponse(items, personalized);
    }

    public record RecommendedPostItem(
            long postId,
            String title,
            double score,
            String reason
    ) {
    }
}
