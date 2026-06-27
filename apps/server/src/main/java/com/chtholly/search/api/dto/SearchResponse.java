package com.chtholly.search.api.dto;

import com.chtholly.post.api.dto.FeedItemResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 搜索响应。 */
@Schema(description = "搜索响应")
public record SearchResponse(
        @Schema(description = "搜索结果") List<FeedItemResponse> items,
        @Schema(description = "下一页游标（Base64URL）") String nextAfter,
        @Schema(description = "是否有更多结果") boolean hasMore,
        @Schema(description = "ES 降级时为 true") boolean degraded
) {
    public SearchResponse(List<FeedItemResponse> items, String nextAfter, boolean hasMore) {
        this(items, nextAfter, hasMore, false);
    }
}
