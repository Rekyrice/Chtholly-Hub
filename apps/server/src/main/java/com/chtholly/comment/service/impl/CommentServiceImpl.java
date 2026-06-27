package com.chtholly.comment.service.impl;

import com.chtholly.comment.api.dto.CommentListResponse;
import com.chtholly.comment.api.dto.CommentResponse;
import com.chtholly.comment.api.dto.CreateCommentRequest;
import com.chtholly.comment.mapper.CommentMapper;
import com.chtholly.comment.model.CommentRow;
import com.chtholly.comment.service.CommentService;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.post.model.Post;
import com.chtholly.post.mapper.PostMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final SnowflakeIdGenerator idGen;

    @Override
    @Transactional(readOnly = true)
    public CommentListResponse listByPost(long postId, Long viewerUserIdNullable) {
        assertCommentablePost(postId, viewerUserIdNullable);
        List<CommentRow> rows = commentMapper.listByPostId(postId);
        List<CommentResponse> tree = buildTree(rows);
        return new CommentListResponse(tree, commentMapper.countByPostId(postId));
    }

    @Override
    @Transactional
    public CommentResponse create(long postId, long userId, CreateCommentRequest request) {
        assertCommentablePost(postId, userId);
        Long parentId = parseParentId(request.parentId());
        if (parentId != null) {
            CommentRow parent = commentMapper.findById(parentId);
            if (parent == null || !postIdEquals(parent.getPostId(), postId)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "回复目标不存在");
            }
            if (parent.getParentId() != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持回复顶级评论");
            }
        }

        long id = idGen.nextId();
        commentMapper.insert(id, postId, parentId, userId, request.content().trim());
        CommentRow row = commentMapper.findById(id);
        return toResponse(row, Collections.emptyList());
    }

    private void assertCommentablePost(long postId, Long viewerUserIdNullable) {
        Post post = postMapper.findById(postId);
        if (post == null || !"published".equals(post.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "帖子不存在或未发布");
        }
        boolean isOwner = viewerUserIdNullable != null
                && post.getCreatorId() != null
                && viewerUserIdNullable.equals(post.getCreatorId());
        if (!"public".equals(post.getVisible()) && !isOwner) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无权查看该帖评论");
        }
    }

    private Long parseParentId(String parentId) {
        if (parentId == null || parentId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(parentId.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "parentId 无效");
        }
    }

    private boolean postIdEquals(Long a, long b) {
        return a != null && a == b;
    }

    private List<CommentResponse> buildTree(List<CommentRow> rows) {
        Map<Long, List<CommentRow>> repliesByParent = new LinkedHashMap<>();
        List<CommentRow> roots = new ArrayList<>();

        for (CommentRow row : rows) {
            if (row.getParentId() == null) {
                roots.add(row);
            } else {
                repliesByParent.computeIfAbsent(row.getParentId(), k -> new ArrayList<>()).add(row);
            }
        }

        List<CommentResponse> out = new ArrayList<>();
        for (CommentRow root : roots) {
            List<CommentResponse> replyDtos = repliesByParent.getOrDefault(root.getId(), List.of()).stream()
                    .map(r -> toResponse(r, Collections.emptyList()))
                    .toList();
            out.add(toResponse(root, replyDtos));
        }
        return out;
    }

    private CommentResponse toResponse(CommentRow row, List<CommentResponse> replies) {
        return new CommentResponse(
                String.valueOf(row.getId()),
                String.valueOf(row.getPostId()),
                row.getParentId() == null ? null : String.valueOf(row.getParentId()),
                String.valueOf(row.getUserId()),
                row.getAuthorNickname(),
                row.getAuthorAvatar(),
                row.getContent(),
                row.getCreatedAt(),
                replies
        );
    }
}
