package com.chtholly.notification.service;

import com.chtholly.notification.api.dto.NotificationListResponse;
import com.chtholly.notification.api.dto.UnreadCountResponse;
import com.chtholly.notification.model.NotificationType;

import java.util.Map;

public interface NotificationService {

    NotificationListResponse list(long userId, int page, int size);

    UnreadCountResponse unreadCount(long userId);

    void markRead(long userId, long notificationId);

    void markAllRead(long userId);

    void create(long recipientUserId, NotificationType type, Map<String, Object> payload);
}
