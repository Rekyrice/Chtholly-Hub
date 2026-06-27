package com.chtholly.comment.service.impl;

import com.chtholly.comment.api.dto.CommentListResponse;
import com.chtholly.comment.api.dto.CommentResponse;
import com.chtholly.comment.api.dto.CreateCommentRequest;
import com.chtholly.comment.mapper.CommentMapper;
import com.chtholly.comment.model.CommentRow;
import com.chtholly.comment.service.CommentContentSanitizer;
import com.chtholly.comment.service.CommentService;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.common.exception.ResourceNotFoundException;
import com.chtholly.notification.event.CommentCreatedEvent;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.post.model.Post;
import com.chtholly.post.mapper.PostMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default implementation of {@link CommentService}.
 * Handles two-level nested comments on published posts with sanitization
 * and {@link CommentCreatedEvent} publishing for downstream notifications.
 */
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    private static final String DELETED_PLACEHOLDER = "该评论已删除";
    private static final int MIN_CONTENT_LENGTH = 1;
    private static final int MAX_CONTENT_LENGTH = 2000;

    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final SnowflakeIdGenerator idGen;
    private final ApplicationEventPublisher eventPublisher;
    private final CommentContentSanitizer contentSanitizer;

    /**
     * Lists paginated root comments and their nested replies for a post.
     *
     * @param postId target post ID
     * @param viewerUserIdNullable optional viewer user ID for visibility checks
     * @param page page number (1-based)
     * @param size page size
     * @return paginated comment tree with total count and has-more flag
     * @throws BusinessException if the post is missing, unpublished, or not visible to the viewer
     */
    @Override
    @Transactional(readOnly = true)
    public CommentListResponse listByPost(long postId, Long viewerUserIdNullable, int page, int size) {
        assertCommentablePost(postId, viewerUserIdNullable);
        long total = commentMapper.countRootByPostId(postId);
        int offset = (page - 1) * size;
        List<CommentRow> roots = commentMapper.listRootByPostId(postId, size, offset);
        boolean hasMore = (long) page * size < total;

        if (roots.isEmpty()) {
            return new CommentListResponse(List.of(), total, page, size, hasMore);
        }

        List<Long> rootIds = roots.stream().map(CommentRow::getId).toList();
        List<CommentRow> level1 = commentMapper.listRepliesByParentIds(postId, rootIds);
        List<Long> level1Ids = level1.stream().map(CommentRow::getId).toList();
        List<CommentRow> level2 = level1Ids.isEmpty()
                ? List.of()
                : commentMapper.listRepliesByParentIds(postId, level1Ids);

        Set<Long> loadedIds = new HashSet<>();
        roots.forEach(r -> loadedIds.add(r.getId()));
        level1.forEach(r -> loadedIds.add(r.getId()));
        level2.forEach(r -> loadedIds.add(r.getId()));

        Set<Long> missingParentIds = new HashSet<>();
        for (CommentRow reply : concat(level1, level2)) {
            if (reply.getParentId() != null && !loadedIds.contains(reply.getParentId())) {
                missingParentIds.add(reply.getParentId());
            }
        }
        List<CommentRow> parentStubs = missingParentIds.isEmpty()
                ? List.of()
                : commentMapper.findByIds(new ArrayList<>(missingParentIds));

        Map<Long, List<CommentRow>> repliesByParent = new LinkedHashMap<>();
        for (CommentRow row : concat(level1, level2)) {
            repliesByParent.computeIfAbsent(row.getParentId(), k -> new ArrayList<>()).add(row);
        }

        List<CommentResponse> items = new ArrayList<>();
        for (CommentRow root : roots) {
            List<CommentResponse> replyDtos = repliesByParent.getOrDefault(root.getId(), List.of()).stream()
                    .map(r -> toResponse(r, repliesByParent.getOrDefault(r.getId(), List.of()).stream()
                            .map(child -> toResponse(child, List.of()))
                            .toList()))
                    .toList();
            items.add(toResponse(root, replyDtos));
        }

        for (CommentRow stub : parentStubs) {
            if (stub.getParentId() != null) {
                continue;
            }
            List<CommentResponse> replyDtos = repliesByParent.getOrDefault(stub.getId(), List.of()).stream()
                    .map(r -> toResponse(r, repliesByParent.getOrDefault(r.getId(), List.of()).stream()
                            .map(child -> toResponse(child, List.of()))
                            .toList()))
                    .toList();
            if (!replyDtos.isEmpty()) {
                items.add(toResponse(stub, replyDtos));
            }
        }

        return new CommentListResponse(items, total, page, size, hasMore);
    }

    /**
     * Creates a root comment or a level-1 reply on a published post.
     *
     * @param postId target post ID
     * @param userId author user ID
     * @param request comment content and optional parent comment ID
     * @return the newly created comment
     * @throws BusinessException if validation fails or the parent is invalid
     */
    @Override
    @Transactional
    public CommentResponse create(long postId, long userId, CreateCommentRequest request) {
        assertCommentablePost(postId, userId);

        String content = validateAndSanitizeContent(request.content());
        Long parentId = parseParentId(request.parentId());
        CommentRow parent = null;
        if (parentId != null) {
            parent = commentMapper.findById(parentId);
            if (parent == null || !postIdEquals(parent.getPostId(), postId)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "回复目标不存在");
            }
            if (parent.getDeletedAt() != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "无法回复已删除的评论");
            }
            if (parent.getParentId() != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持回复顶级评论");
            }
        }

        long id = idGen.nextId();
        commentMapper.insert(id, postId, parentId, userId, content);
        CommentRow row = commentMapper.findById(id);
        Post post = postMapper.findById(postId);
        eventPublisher.publishEvent(new CommentCreatedEvent(
                id,
                postId,
                parentId,
                userId,
                row.getAuthorNickname(),
                row.getAuthorAvatar(),
                post.getCreatorId() == null ? 0L : post.getCreatorId(),
                post.getTitle(),
                post.getSlug(),
                parent == null ? null : parent.getUserId()
        ));
        return toResponse(row, Collections.emptyList());
    }

    /**
     * Soft-deletes a comment when the caller is the comment author or the post author.
     *
     * @param postId target post ID
     * @param commentId comment to delete
     * @param userId requesting user ID
     * @throws ResourceNotFoundException if the comment does not exist
     * @throws BusinessException if the caller lacks permission to delete the comment
     */
    @Override
    @Transactional
    public void delete(long postId, long commentId, long userId) {
        assertCommentablePost(postId, userId);
        CommentRow comment = commentMapper.findById(commentId);
        if (comment == null || !postIdEquals(comment.getPostId(), postId)) {
            throw new ResourceNotFoundException("评论不存在");
        }
        if (comment.getDeletedAt() != null) {
            return;
        }

        Post post = postMapper.findById(postId);
        boolean isCommentAuthor = comment.getUserId() != null && userId == comment.getUserId();
        boolean isPostAuthor = post.getCreatorId() != null && userId == post.getCreatorId();
        if (!isCommentAuthor && !isPostAuthor) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权删除该评论", HttpStatus.FORBIDDEN.value());
        }

        int updated = isCommentAuthor
                ? commentMapper.softDelete(commentId, userId)
                : commentMapper.softDeleteById(commentId);
        if (updated == 0) {
            throw new ResourceNotFoundException("评论不存在");
        }
    }

    private String validateAndSanitizeContent(String raw) {
        String content = contentSanitizer.sanitize(raw);
        if (!StringUtils.hasText(content) || content.length() < MIN_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评论内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评论内容不能超过 " + MAX_CONTENT_LENGTH + " 字");
        }
        return content;
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

    @SafeVarargs
    private static <T> List<T> concat(List<T>... lists) {
        List<T> out = new ArrayList<>();
        for (List<T> list : lists) {
            out.addAll(list);
        }
        return out;
    }

    private CommentResponse toResponse(CommentRow row, List<CommentResponse> replies) {
        String content = row.getDeletedAt() != null ? DELETED_PLACEHOLDER : row.getContent();
        return new CommentResponse(
                String.valueOf(row.getId()),
                String.valueOf(row.getPostId()),
                row.getParentId() == null ? null : String.valueOf(row.getParentId()),
                String.valueOf(row.getUserId()),
                row.getAuthorNickname(),
                row.getAuthorAvatar(),
                content,
                row.getCreatedAt(),
                replies
        );
    }
}
