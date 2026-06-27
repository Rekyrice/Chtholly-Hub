package com.chtholly.comment.service;

import com.chtholly.comment.api.dto.CommentListResponse;
import com.chtholly.comment.api.dto.CommentResponse;
import com.chtholly.comment.api.dto.CreateCommentRequest;

public interface CommentService {

    CommentListResponse listByPost(long postId, Long viewerUserIdNullable);

    CommentResponse create(long postId, long userId, CreateCommentRequest request);
}
