package com.chtholly.post.api.dto;

import java.util.List;

/**
 * 首页 Feed 分页响应。
 */
public record FeedPageResponse(
        List<FeedItemResponse> items,
        int page,
        int size,
        boolean hasMore,
        /** 下一页游标（Base64URL）；无更多数据时为 null。 */
        String nextCursor
) {}