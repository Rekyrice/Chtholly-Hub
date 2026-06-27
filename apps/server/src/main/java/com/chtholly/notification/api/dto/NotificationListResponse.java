package com.chtholly.notification.api.dto;

import java.util.List;

/** 通知分页列表。 */
public record NotificationListResponse(
        List<NotificationResponse> items,
        long total,
        long unreadCount
) {}
