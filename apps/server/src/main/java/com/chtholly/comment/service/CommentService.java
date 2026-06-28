package com.chtholly.comment.service;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.comment.api.dto.CommentResponse;
import com.chtholly.comment.api.dto.CreateCommentRequest;

public interface CommentService {

    PageResponse<CommentResponse> listByPost(long postId, Long viewerUserIdNullable, int page, int size);

    CommentResponse create(long postId, long userId, CreateCommentRequest request);

    void delete(long postId, long commentId, long userId);

    void adminDelete(long postId, long commentId);
}
