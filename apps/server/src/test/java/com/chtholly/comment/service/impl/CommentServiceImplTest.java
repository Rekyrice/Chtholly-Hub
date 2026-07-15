package com.chtholly.comment.service.impl;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.comment.api.dto.CommentResponse;
import com.chtholly.comment.api.dto.CreateCommentRequest;
import com.chtholly.comment.mapper.CommentMapper;
import com.chtholly.comment.model.CommentRow;
import com.chtholly.comment.service.CommentContentSanitizer;
import com.chtholly.common.exception.BusinessException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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

    private CommentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CommentServiceImpl(
                commentMapper, postMapper, idGen, eventPublisher, contentSanitizer);
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

        PageResponse<CommentResponse> response = service.listByPost(1L, null, 2, 10);

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.total()).isEqualTo(25);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).authorHandle()).isEqualTo("tester-handle");
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

        PageResponse<CommentResponse> response = service.listByPost(1L, null, 1, 20);

        assertThat(response.items().get(0).replies().get(0).content()).isEqualTo("该评论已删除");
    }

    @Test
    void given_publishedPost_when_createRootComment_then_postIdAssociated() {
        stubPublishedPost(1L, 10L);
        when(contentSanitizer.sanitize("hello")).thenReturn("hello");
        when(idGen.nextId()).thenReturn(500L);
        CommentRow saved = row(500L, null, "hello");
        when(commentMapper.findById(500L)).thenReturn(saved);

        CommentResponse response = service.create(1L, 9L, new CreateCommentRequest("hello", null));

        verify(commentMapper).insert(500L, 1L, null, 9L, "hello", false);
        assertThat(response.postId()).isEqualTo("1");
        assertThat(response.parentId()).isNull();
    }

    @Test
    void given_validParent_when_createReply_then_parentIdSet() {
        stubPublishedPost(1L, 10L);
        when(contentSanitizer.sanitize("reply")).thenReturn("reply");
        CommentRow parent = row(100L, null, "root");
        when(commentMapper.findById(100L)).thenReturn(parent);
        when(idGen.nextId()).thenReturn(501L);
        CommentRow saved = row(501L, 100L, "reply");
        when(commentMapper.findById(501L)).thenReturn(saved);

        CommentResponse response = service.create(1L, 9L, new CreateCommentRequest("reply", "100"));

        verify(commentMapper).insert(501L, 1L, 100L, 9L, "reply", false);
        assertThat(response.parentId()).isEqualTo("100");
    }

    @Test
    void given_invalidParent_when_createReply_then_rejected() {
        stubPublishedPost(1L, 10L);
        when(contentSanitizer.sanitize("reply")).thenReturn("reply");
        when(commentMapper.findById(200L)).thenReturn(null);

        assertThatThrownBy(() -> service.create(1L, 9L, new CreateCommentRequest("reply", "200")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("回复目标不存在");
    }

    @Test
    void given_replyToReply_when_create_then_rejectedByNestingLimit() {
        stubPublishedPost(1L, 10L);
        when(contentSanitizer.sanitize("nested")).thenReturn("nested");
        CommentRow parent = row(201L, 100L, "level-1-reply");
        when(commentMapper.findById(201L)).thenReturn(parent);

        assertThatThrownBy(() -> service.create(1L, 9L, new CreateCommentRequest("nested", "201")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("顶级评论");
    }

    @Test
    void given_rootAndReplies_when_listByPost_then_buildsParentChildTree() {
        stubPublishedPost(1L, 10L);
        when(commentMapper.countRootByPostId(1L)).thenReturn(1L);

        CommentRow root = row(100L, null, "root");
        when(commentMapper.listRootByPostId(eq(1L), eq(20), eq(0))).thenReturn(List.of(root));

        CommentRow replyA = row(200L, 100L, "reply-a");
        CommentRow replyB = row(201L, 100L, "reply-b");
        when(commentMapper.listRepliesByParentIds(eq(1L), eq(List.of(100L)))).thenReturn(List.of(replyA, replyB));
        when(commentMapper.listRepliesByParentIds(eq(1L), eq(List.of(200L, 201L)))).thenReturn(List.of());

        PageResponse<CommentResponse> response = service.listByPost(1L, null, 1, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).id()).isEqualTo("100");
        assertThat(response.items().get(0).replies()).extracting(CommentResponse::content)
                .containsExactly("reply-a", "reply-b");
    }

    @Test
    void resolvesCorruptedNicknameToHandle() {
        stubPublishedPost(1L, 10L);
        when(commentMapper.countRootByPostId(1L)).thenReturn(1L);

        CommentRow root = row(100L, null, "root");
        root.setAuthorNickname("???");
        root.setAuthorHandle("myhandle");
        when(commentMapper.listRootByPostId(eq(1L), eq(20), eq(0))).thenReturn(List.of(root));
        when(commentMapper.listRepliesByParentIds(eq(1L), eq(List.of(100L)))).thenReturn(List.of());

        PageResponse<CommentResponse> response = service.listByPost(1L, null, 1, 20);

        assertThat(response.items().get(0).authorNickname()).isEqualTo("myhandle");
    }

    @Test
    void chthollyCommentUsesFixedNickname() {
        stubPublishedPost(1L, 10L);
        when(commentMapper.countRootByPostId(1L)).thenReturn(1L);

        CommentRow root = row(100L, null, "ai comment");
        root.setIsChtholly(true);
        root.setAuthorNickname("???");
        when(commentMapper.listRootByPostId(eq(1L), eq(20), eq(0))).thenReturn(List.of(root));
        when(commentMapper.listRepliesByParentIds(eq(1L), eq(List.of(100L)))).thenReturn(List.of());

        PageResponse<CommentResponse> response = service.listByPost(1L, null, 1, 20);

        assertThat(response.items().get(0).authorNickname()).isEqualTo("珂朵莉");
        assertThat(response.items().get(0).chtholly()).isTrue();
    }

    private void stubPublishedPost(long postId, long creatorId) {
        Post post = new Post();
        post.setId(postId);
        post.setCreatorId(creatorId);
        post.setStatus("published");
        post.setVisible("public");
        post.setTitle("title");
        post.setSlug("slug");
        when(postMapper.findById(postId)).thenReturn(post);
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
        row.setAuthorHandle("tester-handle");
        row.setAuthorAvatar(null);
        return row;
    }
}
