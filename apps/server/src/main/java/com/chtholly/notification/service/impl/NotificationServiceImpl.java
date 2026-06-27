package com.chtholly.notification.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.notification.api.dto.NotificationListResponse;
import com.chtholly.notification.api.dto.NotificationResponse;
import com.chtholly.notification.api.dto.UnreadCountResponse;
import com.chtholly.notification.mapper.NotificationMapper;
import com.chtholly.notification.model.NotificationCountStats;
import com.chtholly.notification.model.NotificationRow;
import com.chtholly.notification.model.NotificationType;
import com.chtholly.notification.service.NotificationService;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link NotificationService}.
 * Persists in-app notifications and builds display messages from typed JSON payloads.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final NotificationMapper notificationMapper;
    private final SnowflakeIdGenerator idGen;
    private final ObjectMapper objectMapper;

    /**
     * Returns a paginated list of notifications for a user with aggregate counts.
     *
     * @param userId recipient user ID
     * @param page page number (1-based, clamped to at least 1)
     * @param size page size (clamped to 1–50)
     * @return notification items with total and unread counts
     */
    @Override
    @Transactional(readOnly = true)
    public NotificationListResponse list(long userId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        int offset = (safePage - 1) * safeSize;

        List<NotificationResponse> items = notificationMapper.listByUser(userId, safeSize, offset).stream()
                .map(this::toResponse)
                .toList();
        NotificationCountStats stats = notificationMapper.countStatsByUser(userId);
        long total = stats == null ? 0L : stats.getTotal();
        long unread = stats == null ? 0L : stats.getUnread();
        return new NotificationListResponse(items, total, unread);
    }

    /**
     * Returns the unread notification count for a user.
     *
     * @param userId recipient user ID
     * @return unread count wrapper
     */
    @Override
    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(long userId) {
        return new UnreadCountResponse(notificationMapper.countUnread(userId));
    }

    /**
     * Marks a single notification as read for the owning user.
     *
     * @param userId recipient user ID
     * @param notificationId notification to mark read
     * @throws BusinessException if the notification does not exist or belongs to another user
     */
    @Override
    @Transactional
    public void markRead(long userId, long notificationId) {
        NotificationRow row = notificationMapper.findById(notificationId);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "通知不存在");
        }
        notificationMapper.markRead(notificationId, userId);
    }

    /**
     * Marks all notifications as read for a user.
     *
     * @param userId recipient user ID
     */
    @Override
    @Transactional
    public void markAllRead(long userId) {
        notificationMapper.markAllRead(userId);
    }

    /**
     * Persists a new notification with a JSON-serialized payload.
     *
     * @param recipientUserId target user ID
     * @param type notification type
     * @param payload structured payload fields for display and deep links
     * @throws BusinessException if payload serialization or persistence fails
     */
    @Override
    @Transactional
    public void create(long recipientUserId, NotificationType type, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            notificationMapper.insert(idGen.nextId(), recipientUserId, type.name(), json);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "写入通知失败");
        }
    }

    /**
     * Checks whether the user has an unread like notification for a specific post.
     *
     * @param userId recipient user ID
     * @param postId liked post ID
     * @return {@code true} if an unread like notification exists
     */
    @Override
    @Transactional(readOnly = true)
    public boolean hasUnreadLikePost(long userId, long postId) {
        return notificationMapper.countUnreadLikePost(userId, NotificationType.LIKE_POST.name(), postId) > 0;
    }

    /**
     * Deletes read notifications older than the retention window for one user.
     *
     * @param userId recipient user ID
     * @param retentionDays age threshold in days for eligible read notifications
     * @return number of rows deleted
     */
    @Override
    @Transactional
    public int cleanExpired(long userId, int retentionDays) {
        return notificationMapper.deleteExpiredReadByUser(userId, retentionDays);
    }

    /**
     * Deletes read notifications older than the retention window for all users.
     *
     * @param retentionDays age threshold in days for eligible read notifications
     * @return number of rows deleted
     */
    @Override
    @Transactional
    public int cleanExpiredAll(int retentionDays) {
        return notificationMapper.deleteExpiredRead(retentionDays);
    }

    private NotificationResponse toResponse(NotificationRow row) {
        Map<String, Object> payload = parsePayload(row.getPayload());
        NotificationType type = parseType(row.getType());
        String actorNickname = str(payload.get("actorNickname"));
        String postTitle = str(payload.get("postTitle"));
        String message = buildMessage(type, actorNickname, postTitle);

        return new NotificationResponse(
                String.valueOf(row.getId()),
                type == NotificationType.UNKNOWN ? row.getType() : type.name(),
                str(payload.get("actorUserId")),
                actorNickname,
                str(payload.get("actorAvatar")),
                str(payload.get("postId")),
                str(payload.get("postSlug")),
                postTitle,
                str(payload.get("commentId")),
                message,
                row.getCreatedAt(),
                row.getReadAt()
        );
    }

    private NotificationType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return NotificationType.UNKNOWN;
        }
        try {
            return NotificationType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            log.warn("未知通知类型: {}", raw);
            return NotificationType.UNKNOWN;
        }
    }

    private Map<String, Object> parsePayload(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, PAYLOAD_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String buildMessage(NotificationType type, String actor, String postTitle) {
        String name = actor == null || actor.isBlank() ? "有人" : actor;
        return switch (type) {
            case COMMENT_POST -> postTitle == null || postTitle.isBlank()
                    ? name + " 评论了你的帖子"
                    : name + " 评论了《" + postTitle + "》";
            case COMMENT_REPLY -> name + " 回复了你的评论";
            case LIKE_POST -> postTitle == null || postTitle.isBlank()
                    ? name + " 赞了你的帖子"
                    : name + " 赞了《" + postTitle + "》";
            case FOLLOW -> name + " 关注了你";
            case UNKNOWN -> "你有一条新通知";
        };
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
