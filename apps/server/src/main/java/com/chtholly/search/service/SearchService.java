package com.chtholly.search.service;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.search.api.dto.HubFeedResponse;
import com.chtholly.search.api.dto.SuggestResponse;

/**
 * 搜索服务接口：封装检索与联想能力，供控制器调用。
 */
public interface SearchService {
    /**
     * 关键词检索。
     * @param q 关键词
     * @param size 返回条数
     * @param tagsCsv 标签过滤（CSV）
     * @param after 游标（Base64URL）
     * @param currentUserIdNullable 当前用户ID（可空）
     */
    PageResponse<FeedItemResponse> search(String q, int size, String tagsCsv, String after, Long currentUserIdNullable);

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
     * 联想建议（Completion Suggester）。
     * @param prefix 前缀
     * @param size 返回条数
     */
    SuggestResponse suggest(String prefix, int size);
}
