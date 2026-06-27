package com.chtholly.comment.api.dto;

import java.time.Instant;
import java.util.List;

/** 单条评论（顶级或回复）。 */
public record CommentResponse(
        String id,
        String postId,
        String parentId,
        String userId,
        String authorNickname,
        String authorAvatar,
        String content,
        Instant createdAt,
        List<CommentResponse> replies
) {}
