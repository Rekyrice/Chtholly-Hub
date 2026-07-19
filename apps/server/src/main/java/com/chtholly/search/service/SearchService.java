package com.chtholly.search.service;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.search.api.dto.HubFeedResponse;
import com.chtholly.search.api.dto.SuggestResponse;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 搜索服务接口：封装检索与联想能力，供控制器调用。
 */
public interface SearchService {
    /**
     * Searches published posts with optional tag filtering and cursor pagination.
     *
     * @param q query text
     * @param size maximum result count
     * @param tagsCsv optional comma-separated tag filter
     * @param after optional Base64URL cursor
     * @param sort requested result ordering
     * @param currentUserIdNullable optional current user ID
     * @return matching post page
     */
    PageResponse<FeedItemResponse> search(
            String q, int size, String tagsCsv, String after,
            SearchSort sort, Long currentUserIdNullable);

    /** Maps domain entity names to published articles using the structured analysis index. */
    PageResponse<FeedItemResponse> searchByEntityNames(
            List<String> entityNames, int size, Long currentUserIdNullable);

    /**
     * Aggregates Hub page search-backed regions with one msearch request.
     *
     * @param interestTags optional user interest tags for recommendations
     * @param currentUserIdNullable current user ID for liked/faved enrichment
     * @param page 1-indexed latest-posts page
     * @param size latest-posts page size
     */
    HubFeedResponse hubFeed(String interestTags, Long currentUserIdNullable, int page, int size);

    /**
     * 按兴趣标签权重推荐文章（function_score）。
     */
    List<FeedItemResponse> recommendByInterest(Map<String, Double> tagWeights,
                                               Collection<Long> excludePostIds,
                                               int limit,
                                               Long currentUserIdNullable);

    /**
     * 热门文章 fallback（按 like_count 排序）。
     */
    List<FeedItemResponse> recommendHot(Collection<Long> excludePostIds,
                                        int limit,
                                        Long currentUserIdNullable);

    /**
     * 基于种子帖子的标签/实体相似推荐。
     */
    List<FeedItemResponse> recommendSimilarToPost(long sourcePostId,
                                                  Collection<Long> excludePostIds,
                                                  int limit,
                                                  Long currentUserIdNullable);

    /**
     * 联想建议（Completion Suggester）。
     * @param prefix 前缀
     * @param size 返回条数
     */
    SuggestResponse suggest(String prefix, int size);
}
