package com.chtholly.notification.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.notification.api.dto.NotificationListResponse;
import com.chtholly.notification.api.dto.NotificationResponse;
import com.chtholly.notification.api.dto.UnreadCountResponse;
import com.chtholly.notification.mapper.NotificationMapper;
import com.chtholly.notification.model.NotificationRow;
import com.chtholly.notification.model.NotificationType;
import com.chtholly.notification.service.NotificationService;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final NotificationMapper notificationMapper;
    private final SnowflakeIdGenerator idGen;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public NotificationListResponse list(long userId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        int offset = (safePage - 1) * safeSize;

        List<NotificationResponse> items = notificationMapper.listByUser(userId, safeSize, offset).stream()
                .map(this::toResponse)
                .toList();
        long total = notificationMapper.countByUser(userId);
        long unread = notificationMapper.countUnread(userId);
        return new NotificationListResponse(items, total, unread);
    }

    @Override
    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(long userId) {
        return new UnreadCountResponse(notificationMapper.countUnread(userId));
    }

    @Override
    @Transactional
    public void markRead(long userId, long notificationId) {
        NotificationRow row = notificationMapper.findById(notificationId);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "通知不存在");
        }
        notificationMapper.markRead(notificationId, userId);
    }

    @Override
    @Transactional
    public void markAllRead(long userId) {
        notificationMapper.markAllRead(userId);
    }

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

    private NotificationResponse toResponse(NotificationRow row) {
        Map<String, Object> payload = parsePayload(row.getPayload());
        NotificationType type = NotificationType.valueOf(row.getType());
        String actorNickname = str(payload.get("actorNickname"));
        String postTitle = str(payload.get("postTitle"));
        String message = buildMessage(type, actorNickname, postTitle);

        return new NotificationResponse(
                String.valueOf(row.getId()),
                row.getType(),
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
        };
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
