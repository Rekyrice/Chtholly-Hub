package com.chtholly.notification.event;

/** 评论创建后发布，用于异步写入通知。 */
public record CommentCreatedEvent(
        long commentId,
        long postId,
        Long parentId,
        long authorUserId,
        String authorNickname,
        String authorAvatar,
        long postCreatorId,
        String postTitle,
        String postSlug,
        Long parentCommentUserId
) {}
