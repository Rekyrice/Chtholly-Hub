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

    /** 是否已有相同帖子的未读点赞通知（去重用）。 */
    boolean hasUnreadLikePost(long userId, long postId);

    /** 删除指定用户超过 retentionDays 天的已读通知。 */
    int cleanExpired(long userId, int retentionDays);

    /** 全局清理超过 retentionDays 天的已读通知。 */
    int cleanExpiredAll(int retentionDays);
}
