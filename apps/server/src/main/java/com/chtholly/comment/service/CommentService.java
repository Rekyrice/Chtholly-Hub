package com.chtholly.comment.service;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.comment.api.dto.CommentResponse;
import com.chtholly.comment.api.dto.CreateCommentRequest;
import com.chtholly.comment.api.dto.UserCommentActivityResponse;

import java.util.List;
import java.util.Map;

public interface CommentService {

    PageResponse<CommentResponse> listByPost(long postId, Long viewerUserIdNullable, int page, int size);

    /**
     * Lists one user's comments that are visible on published public posts.
     *
     * @param userId comment author ID
     * @param page page number (1-based)
     * @param size page size
     * @return paginated public comment activity
     */
    PageResponse<UserCommentActivityResponse> listByUser(long userId, int page, int size);

    /**
     * Counts active comment rows for each post, including replies.
     *
     * @param postIds post IDs to count
     * @return one non-negative count for every distinct non-null post ID
     */
    Map<Long, Long> countActiveByPostIds(List<Long> postIds);

    CommentResponse create(long postId, long userId, CreateCommentRequest request);

    void delete(long postId, long commentId, long userId);

    void adminDelete(long postId, long commentId);
}
