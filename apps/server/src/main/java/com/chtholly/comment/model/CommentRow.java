package com.chtholly.comment.model;

import lombok.Data;

import java.time.Instant;

/** 评论查询行（含作者信息）。 */
@Data
public class CommentRow {
    private Long id;
    private Long postId;
    private Long parentId;
    private Long userId;
    private String content;
    private Instant createdAt;
    private String authorNickname;
    private String authorAvatar;
    private Instant deletedAt;
    private Instant updatedAt;
    /** 珂朵莉 AI 生成的评论。 */
    private Boolean isChtholly;
}
