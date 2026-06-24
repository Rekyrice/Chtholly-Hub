package com.chtholly.post.service;

import com.chtholly.post.api.dto.FeedPageResponse;

/**
 * 知文 Feed 业务接口。
 */
public interface PostFeedService {
    FeedPageResponse getPublicFeed(int page, int size, Long currentUserIdNullable);

    FeedPageResponse getMyPublished(long userId, int page, int size);
}