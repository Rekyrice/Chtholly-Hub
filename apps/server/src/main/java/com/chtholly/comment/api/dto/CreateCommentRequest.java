package com.chtholly.comment.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建评论请求。 */
@Schema(description = "创建评论请求")
public record CreateCommentRequest(
        @Schema(description = "评论内容，1-2000 字") @NotBlank @Size(min = 1, max = 2000) String content,
        @Schema(description = "父评论 ID（回复时填写，顶级评论留空）") String parentId
) {}
