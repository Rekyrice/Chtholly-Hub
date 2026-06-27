package com.chtholly.post.service;

import com.chtholly.post.api.dto.FeedPageResponse;

/**
 * 帖子 Feed 业务接口。
 */
public interface PostFeedService {
    FeedPageResponse getPublicFeed(Integer page, String cursor, int size, Long ownerId, String tag, Long currentUserIdNullable);

    FeedPageResponse getMyPublished(long userId, int page, int size);

    /** 关注时间线：推拉结合，合并推模式 timeline 与大 V 拉模式文章。 */
    FeedPageResponse getFollowingFeed(long userId, int page, int size);

    /** 公开 Feed 页面缓存 Key，用于 ETag 计算。 */
    String publicFeedPageKey(Integer page, String cursor, int size, Long ownerId, String tag);
}