package com.chtholly.comment.api.dto;

import java.time.Instant;

/**
 * Represents one public comment activity item together with its post metadata.
 *
 * @param id comment ID
 * @param postId post ID
 * @param postSlug public post slug
 * @param postTitle public post title
 * @param parentId parent comment ID, or {@code null} for a top-level comment
 * @param content comment content
 * @param createdAt comment creation time
 */
public record UserCommentActivityResponse(
        String id,
        String postId,
        String postSlug,
        String postTitle,
        String parentId,
        String content,
        Instant createdAt
) {
}
