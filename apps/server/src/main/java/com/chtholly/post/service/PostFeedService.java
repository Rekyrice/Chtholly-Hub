package com.chtholly.post.service;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;

/**
 * 帖子 Feed 业务接口。
 */
public interface PostFeedService {
    PageResponse<FeedItemResponse> getPublicFeed(Integer page, String cursor, int size, Long ownerId, String tag, Long currentUserIdNullable);

    PageResponse<FeedItemResponse> getMyPublished(long userId, int page, int size);

    /** 关注时间线：推拉结合，合并推模式 timeline 与大 V 拉模式文章。 */
    PageResponse<FeedItemResponse> getFollowingFeed(long userId, int page, int size);

    /**
     * Invalidates L1/L2 caches for a user's personal published feed.
     *
     * @param userId owner whose {@code feed:mine:*} pages should be dropped
     */
    void invalidateMyPublishedCache(long userId);

    /** 公开 Feed 页面缓存 Key，用于 ETag 计算。 */
    String publicFeedPageKey(Integer page, String cursor, int size, Long ownerId, String tag);
}