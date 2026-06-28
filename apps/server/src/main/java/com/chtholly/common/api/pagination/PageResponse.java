package com.chtholly.common.api.pagination;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 统一分页响应：offset 模式填充 page/total，cursor 模式填充 nextCursor。
 */
@Schema(description = "统一分页响应")
public record PageResponse<T>(
        @Schema(description = "数据列表") List<T> items,
        @Schema(description = "当前页码（offset 模式；游标模式为 0）") int page,
        @Schema(description = "每页大小") int size,
        @Schema(description = "总数（offset 模式；游标模式为 0）") long total,
        @Schema(description = "是否有更多") boolean hasMore,
        @Schema(description = "下一页游标（cursor 模式）") String nextCursor,
        @Schema(description = "搜索 ES 降级标记") @JsonInclude(JsonInclude.Include.NON_NULL) Boolean degraded
) {
    public PageResponse(List<T> items, int page, int size, long total, boolean hasMore, String nextCursor) {
        this(items, page, size, total, hasMore, nextCursor, null);
    }

    /** 搜索 API 向后兼容：同时输出 nextAfter 字段。 */
    @JsonProperty("nextAfter")
    public String nextAfter() {
        return nextCursor;
    }

    public static <T> PageResponse<T> offset(List<T> items, int page, int size, long total) {
        boolean hasMore = (long) page * size < total;
        return new PageResponse<>(items, page, size, total, hasMore, null);
    }

    public static <T> PageResponse<T> offset(List<T> items, int page, int size, long total, boolean hasMore) {
        return new PageResponse<>(items, page, size, total, hasMore, null);
    }

    public static <T> PageResponse<T> offset(List<T> items, int page, int size, long total,
                                             boolean hasMore, String nextCursor) {
        return new PageResponse<>(items, page, size, total, hasMore, nextCursor);
    }

    public static <T> PageResponse<T> cursor(List<T> items, int size, boolean hasMore, String nextCursor) {
        return new PageResponse<>(items, 0, size, 0L, hasMore, nextCursor);
    }
}
