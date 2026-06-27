package com.chtholly.comment.service.impl;

import com.chtholly.comment.api.dto.CommentListResponse;
import com.chtholly.comment.mapper.CommentMapper;
import com.chtholly.comment.model.CommentRow;
import com.chtholly.comment.service.CommentContentSanitizer;
import com.chtholly.comment.service.CommentRateLimiter;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.post.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {

    @Mock
    private CommentMapper commentMapper;
    @Mock
    private PostMapper postMapper;
    @Mock
    private SnowflakeIdGenerator idGen;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private CommentContentSanitizer contentSanitizer;
    @Mock
    private CommentRateLimiter commentRateLimiter;

    private CommentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CommentServiceImpl(
                commentMapper, postMapper, idGen, eventPublisher, contentSanitizer, commentRateLimiter);
    }

    @Test
    void listByPostPaginatesRootsAndLoadsReplies() {
        Post post = new Post();
        post.setStatus("published");
        post.setVisible("public");
        when(postMapper.findById(1L)).thenReturn(post);
        when(commentMapper.countRootByPostId(1L)).thenReturn(25L);

        CommentRow root1 = row(100L, null, "root-1");
        CommentRow root2 = row(101L, null, "root-2");
        when(commentMapper.listRootByPostId(eq(1L), eq(10), eq(10))).thenReturn(List.of(root1, root2));

        CommentRow reply = row(200L, 100L, "reply-1");
        when(commentMapper.listRepliesByParentIds(eq(1L), eq(List.of(100L, 101L)))).thenReturn(List.of(reply));
        when(commentMapper.listRepliesByParentIds(eq(1L), eq(List.of(200L)))).thenReturn(List.of());

        CommentListResponse response = service.listByPost(1L, null, 2, 10);

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.total()).isEqualTo(25);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).replies()).hasSize(1);
        assertThat(response.items().get(0).replies().get(0).content()).isEqualTo("reply-1");
    }

    @Test
    void masksDeletedReplyContent() {
        Post post = new Post();
        post.setStatus("published");
        post.setVisible("public");
        when(postMapper.findById(1L)).thenReturn(post);
        when(commentMapper.countRootByPostId(1L)).thenReturn(1L);

        CommentRow root = row(100L, null, "root");
        when(commentMapper.listRootByPostId(eq(1L), eq(20), eq(0))).thenReturn(List.of(root));

        CommentRow deletedReply = row(200L, 100L, "secret");
        deletedReply.setDeletedAt(Instant.now());
        when(commentMapper.listRepliesByParentIds(eq(1L), eq(List.of(100L)))).thenReturn(List.of(deletedReply));
        when(commentMapper.listRepliesByParentIds(eq(1L), eq(List.of(200L)))).thenReturn(List.of());

        CommentListResponse response = service.listByPost(1L, null, 1, 20);

        assertThat(response.items().get(0).replies().get(0).content()).isEqualTo("该评论已删除");
    }

    private static CommentRow row(long id, Long parentId, String content) {
        CommentRow row = new CommentRow();
        row.setId(id);
        row.setPostId(1L);
        row.setParentId(parentId);
        row.setUserId(9L);
        row.setContent(content);
        row.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        row.setAuthorNickname("tester");
        row.setAuthorAvatar(null);
        return row;
    }
}
