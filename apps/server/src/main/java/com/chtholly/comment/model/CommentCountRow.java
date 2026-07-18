package com.chtholly.comment.model;

/** Aggregate count of active comment rows for one post. */
public record CommentCountRow(Long postId, Long commentCount) {
}
