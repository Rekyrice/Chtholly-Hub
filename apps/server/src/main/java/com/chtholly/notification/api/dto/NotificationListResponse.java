package com.chtholly.notification.api.dto;

import com.chtholly.common.api.pagination.PageResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 通知分页列表（统一 PageResponse 字段 + 未读数）。 */
@Schema(description = "通知列表")
public record NotificationListResponse(
        @Schema(description = "通知条目") List<NotificationResponse> items,
        @Schema(description = "当前页码") int page,
        @Schema(description = "每页条数") int size,
        @Schema(description = "总数") long total,
        @Schema(description = "是否有下一页") boolean hasMore,
        @Schema(description = "下一页游标（通知列表为 offset 模式，恒为 null）") String nextCursor,
        @Schema(description = "未读数") long unreadCount
) {
    public static NotificationListResponse from(PageResponse<NotificationResponse> page, long unreadCount) {
        return new NotificationListResponse(
                page.items(),
                page.page(),
                page.size(),
                page.total(),
                page.hasMore(),
                page.nextCursor(),
                unreadCount
        );
    }
}
