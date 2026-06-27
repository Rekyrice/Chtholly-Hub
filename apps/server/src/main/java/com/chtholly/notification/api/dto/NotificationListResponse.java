package com.chtholly.notification.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 通知分页列表。 */
@Schema(description = "通知列表")
public record NotificationListResponse(
        @Schema(description = "通知条目") List<NotificationResponse> items,
        @Schema(description = "总数") long total,
        @Schema(description = "未读数") long unreadCount
) {}
