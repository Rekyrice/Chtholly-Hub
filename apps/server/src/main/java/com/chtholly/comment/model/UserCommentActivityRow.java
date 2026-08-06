package com.chtholly.comment.model;

import lombok.Data;

import java.time.Instant;

/** Public comment activity projected with its post metadata. */
@Data
public class UserCommentActivityRow {
    private Long id;
    private Long postId;
    private String postSlug;
    private String postTitle;
    private Long parentId;
    private String content;
    private Instant createdAt;
}
