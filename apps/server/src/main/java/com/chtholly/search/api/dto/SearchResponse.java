package com.chtholly.search.api.dto;

import com.chtholly.post.api.dto.FeedItemResponse;
import java.util.List;

/**
 * 搜索响应：包含结果列表与分页游标。
 */
public record SearchResponse(
        List<FeedItemResponse> items,
        String nextAfter,
        boolean hasMore,
        /** ES 不可用或查询失败时为 true，前端可提示搜索降级。 */
        boolean degraded
) {
    public SearchResponse(List<FeedItemResponse> items, String nextAfter, boolean hasMore) {
        this(items, nextAfter, hasMore, false);
    }
}