package com.chtholly.comment.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 评论分页列表。 */
@Schema(description = "评论分页列表")
public record CommentListResponse(
        @Schema(description = "顶级评论列表") List<CommentResponse> items,
        @Schema(description = "顶级评论总数") long total,
        @Schema(description = "当前页码") int page,
        @Schema(description = "每页条数") int size,
        @Schema(description = "是否有下一页") boolean hasMore
) {}
