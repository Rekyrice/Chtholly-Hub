package com.chtholly.comment.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** 单条评论（顶级或回复）。 */
@Schema(description = "评论")
public record CommentResponse(
        @Schema(description = "评论 ID") String id,
        @Schema(description = "帖子 ID") String postId,
        @Schema(description = "父评论 ID") String parentId,
        @Schema(description = "作者用户 ID") String userId,
        @Schema(description = "作者昵称") String authorNickname,
        @Schema(description = "作者头像") String authorAvatar,
        @Schema(description = "评论内容") String content,
        @Schema(description = "创建时间") Instant createdAt,
        @Schema(description = "子回复列表") List<CommentResponse> replies
) {}
