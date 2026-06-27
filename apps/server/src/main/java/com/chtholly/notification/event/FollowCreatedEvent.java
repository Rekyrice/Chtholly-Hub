package com.chtholly.notification.event;

/** 关注成功后发布，用于写入通知。 */
public record FollowCreatedEvent(
        long fromUserId,
        String fromNickname,
        String fromAvatar,
        long toUserId
) {}
