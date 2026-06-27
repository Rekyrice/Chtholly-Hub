package com.chtholly.admin.service;

import com.chtholly.admin.audit.AdminAction;
import com.chtholly.comment.service.CommentService;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.common.exception.ResourceNotFoundException;
import com.chtholly.post.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminPostService {

    private final PostService postService;
    private final CommentService commentService;
    private final AdminAuditService auditService;

    public void updateVisibility(long adminUserId, long postId, String visible) {
        postService.adminUpdateVisibility(postId, visible);
        auditService.record(adminUserId, AdminAction.HIDE_POST, "POST", postId,
                Map.of("visible", visible));
    }

    public void deletePost(long adminUserId, long postId) {
        try {
            postService.adminDelete(postId);
        } catch (BusinessException ex) {
            if (ex.getErrorCode() == ErrorCode.BAD_REQUEST) {
                throw new ResourceNotFoundException("帖子不存在");
            }
            throw ex;
        }
        auditService.record(adminUserId, AdminAction.DELETE_POST, "POST", postId, Map.of());
    }

    public void deleteComment(long adminUserId, long postId, long commentId) {
        commentService.adminDelete(postId, commentId);
        auditService.record(adminUserId, AdminAction.DELETE_COMMENT, "COMMENT", commentId,
                Map.of("postId", postId));
    }
}
