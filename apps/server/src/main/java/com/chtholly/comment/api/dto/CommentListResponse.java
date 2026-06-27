package com.chtholly.comment.api.dto;

import java.util.List;

public record CommentListResponse(
        List<CommentResponse> items,
        long total,
        int page,
        int size,
        boolean hasMore
) {}
