package com.chtholly.notification.api.dto;

import java.time.Instant;

/** 单条通知。 */
public record NotificationResponse(
        String id,
        String type,
        String actorUserId,
        String actorNickname,
        String actorAvatar,
        String postId,
        String postSlug,
        String postTitle,
        String commentId,
        String message,
        Instant createdAt,
        Instant readAt
) {}
