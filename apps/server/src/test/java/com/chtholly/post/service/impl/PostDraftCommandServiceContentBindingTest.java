package com.chtholly.post.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostDraftCommandServiceContentBindingTest {

    @Mock
    private PostMapper mapper;
    @Mock
    private SnowflakeIdGenerator idGenerator;
    @Mock
    private StorageService storageService;
    @Mock
    private PostOutboxWriter outboxWriter;
    @Mock
    private PostCommittedSideEffectCoordinator sideEffectCoordinator;
    @Mock
    private PostMutationCacheCoordinator cacheCoordinator;

    private PostDraftCommandService service;

    @BeforeEach
    void setUp() {
        service = new PostDraftCommandService(
                mapper,
                idGenerator,
                storageService,
                outboxWriter,
                sideEffectCoordinator,
                cacheCoordinator);
    }

    @Test
    void prepareContentBinding_alwaysSuspendsAnyCallerTransactionDuringStorageVerification()
            throws NoSuchMethodException {
        Transactional transaction = PostDraftCommandService.class
                .getMethod(
                        "prepareContentBinding",
                        long.class,
                        long.class,
                        String.class,
                        String.class,
                        Long.class,
                        String.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    void prepareContentBinding_whenUploadKeyBelongsToAnotherPost_thenRejectsBeforeDatabaseRead() {
        long postId = 42L;
        assertThatThrownBy(() -> service.prepareContentBinding(
                7L,
                postId,
                "posts/43/content-uploads/" + "a".repeat(32) + ".md",
                "etag",
                5L,
                "b".repeat(64)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getMessage()).isEqualTo("正文对象不属于该文章"));

        verify(mapper, never()).findById(postId);
        verify(mapper, never()).findDraftByIdForUpdate(postId);
        verify(mapper, never()).updateContent(any());
    }

    @Test
    void prepareContentBinding_whenSha256IsInvalid_thenRejectsBeforeDatabaseRead() {
        long postId = 42L;

        assertThatThrownBy(() -> service.prepareContentBinding(
                7L,
                postId,
                "posts/42/content-uploads/" + "a".repeat(32) + ".md",
                "etag",
                5L,
                "not-a-digest"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getMessage()).isEqualTo("正文摘要非法"));

        verify(mapper, never()).findById(postId);
        verify(mapper, never()).findDraftByIdForUpdate(postId);
        verify(mapper, never()).updateContent(any());
    }

    @Test
    void prepareContentBinding_whenSizeIsMissing_thenRejectsBeforeDatabaseRead() {
        long postId = 42L;

        assertThatThrownBy(() -> service.prepareContentBinding(
                7L,
                postId,
                "posts/42/content-uploads/" + "a".repeat(32) + ".md",
                "etag",
                null,
                "b".repeat(64)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getMessage()).isEqualTo("正文大小非法"));

        verify(mapper, never()).findById(postId);
        verify(mapper, never()).findDraftByIdForUpdate(postId);
        verify(mapper, never()).updateContent(any());
    }

    @Test
    void prepareContentBinding_whenObjectDoesNotExist_thenNeverAcquiresRowLock() throws IOException {
        long postId = 42L;
        String objectKey = "posts/42/content-uploads/" + "a".repeat(32) + ".md";
        String sha256 = "b".repeat(64);
        when(mapper.findById(postId)).thenReturn(ownedDraft(postId));
        when(storageService.objectMatches(objectKey, sha256, 5L)).thenReturn(false);

        assertThatThrownBy(() -> service.prepareContentBinding(
                7L, postId, objectKey, "etag", 5L, sha256))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getMessage()).isEqualTo("正文对象不存在或校验失败"));

        verify(mapper, never()).findDraftByIdForUpdate(postId);
        verify(mapper, never()).updateContent(any());
    }

    @Test
    void prepareContentBinding_whenObjectDigestDoesNotMatch_thenNeverAcquiresRowLock() throws IOException {
        long postId = 42L;
        String objectKey = "posts/42/content-uploads/" + "a".repeat(32) + ".md";
        String requestedSha256 = "c".repeat(64);
        when(mapper.findById(postId)).thenReturn(ownedDraft(postId));
        when(storageService.objectMatches(objectKey, requestedSha256, 5L)).thenReturn(false);

        assertThatThrownBy(() -> service.prepareContentBinding(
                7L, postId, objectKey, "etag", 5L, requestedSha256))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getMessage()).isEqualTo("正文对象不存在或校验失败"));

        verify(mapper, never()).findDraftByIdForUpdate(postId);
        verify(mapper, never()).updateContent(any());
    }

    @Test
    void bindPreparedContent_whenObjectMatches_thenUsesStorageResolvedPublicUrl() throws IOException {
        long postId = 42L;
        String objectKey = "posts/42/content-uploads/" + "a".repeat(32) + ".md";
        String sha256 = "b".repeat(64);
        when(mapper.findById(postId)).thenReturn(ownedDraft(postId));
        when(mapper.findDraftByIdForUpdate(postId)).thenReturn(ownedDraft(postId));
        when(storageService.objectMatches(objectKey, sha256, 5L)).thenReturn(true);
        when(storageService.resolvePublicUrl(objectKey)).thenReturn("/uploads/" + objectKey);
        when(mapper.updateContent(any())).thenReturn(1);

        PostDraftCommandService.PreparedContentBinding prepared = service.prepareContentBinding(
                7L, postId, objectKey, "etag", 5L, sha256);
        service.bindPreparedContent(prepared);

        ArgumentCaptor<Post> boundPost = ArgumentCaptor.forClass(Post.class);
        verify(mapper).updateContent(boundPost.capture());
        assertThat(boundPost.getValue().getContentUrl()).isEqualTo("/uploads/" + objectKey);
        verify(storageService).objectMatches(objectKey, sha256, 5L);
    }

    @Test
    void bindPreparedContent_whenHistoricalKeyMatches_thenRemainsCompatible() throws IOException {
        long postId = 42L;
        String objectKey = "posts/42/content.md";
        String sha256 = "d".repeat(64);
        when(mapper.findById(postId)).thenReturn(ownedDraft(postId));
        when(mapper.findDraftByIdForUpdate(postId)).thenReturn(ownedDraft(postId));
        when(storageService.objectMatches(objectKey, sha256, 5L)).thenReturn(true);
        when(storageService.resolvePublicUrl(objectKey)).thenReturn("/uploads/" + objectKey);
        when(mapper.updateContent(any())).thenReturn(1);

        PostDraftCommandService.PreparedContentBinding prepared = service.prepareContentBinding(
                7L, postId, objectKey, "etag", 5L, sha256);
        service.bindPreparedContent(prepared);

        verify(mapper).updateContent(any());
        verify(storageService).objectMatches(objectKey, sha256, 5L);
    }

    @Test
    void contentBinding_verifiesObjectBeforeAcquiringTheDraftRowLock() throws IOException {
        long postId = 42L;
        String objectKey = "posts/42/content-uploads/" + "a".repeat(32) + ".md";
        String sha256 = "b".repeat(64);
        when(mapper.findById(postId)).thenReturn(ownedDraft(postId));
        when(mapper.findDraftByIdForUpdate(postId)).thenReturn(ownedDraft(postId));
        when(storageService.objectMatches(objectKey, sha256, 5L)).thenReturn(true);
        when(storageService.resolvePublicUrl(objectKey)).thenReturn("/uploads/" + objectKey);
        when(mapper.updateContent(any())).thenReturn(1);

        PostDraftCommandService.PreparedContentBinding prepared = service.prepareContentBinding(
                7L, postId, objectKey, "etag", 5L, sha256);
        service.bindPreparedContent(prepared);

        InOrder order = inOrder(mapper, storageService);
        order.verify(mapper).findById(postId);
        order.verify(storageService).objectMatches(objectKey, sha256, 5L);
        order.verify(storageService).resolvePublicUrl(objectKey);
        order.verify(mapper).findDraftByIdForUpdate(postId);
        order.verify(mapper).updateContent(any());
    }

    @Test
    void bindPreparedContent_whenDraftOwnershipChangesAfterVerification_thenRejectsUnderLock() throws IOException {
        long postId = 42L;
        String objectKey = "posts/42/content-uploads/" + "a".repeat(32) + ".md";
        String sha256 = "b".repeat(64);
        when(mapper.findById(postId)).thenReturn(ownedDraft(postId));
        when(storageService.objectMatches(objectKey, sha256, 5L)).thenReturn(true);
        when(storageService.resolvePublicUrl(objectKey)).thenReturn("/uploads/" + objectKey);
        when(mapper.findDraftByIdForUpdate(postId)).thenReturn(Post.builder()
                .id(postId)
                .creatorId(8L)
                .status("draft")
                .build());

        PostDraftCommandService.PreparedContentBinding prepared = service.prepareContentBinding(
                7L, postId, objectKey, "etag", 5L, sha256);

        assertThatThrownBy(() -> service.bindPreparedContent(prepared))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getMessage()).isEqualTo("草稿不存在或无权限"));

        verify(mapper, never()).updateContent(any());
        verify(outboxWriter, never()).write(anyLong(), anyString(), anyString());
    }

    private static Post ownedDraft(long postId) {
        return Post.builder()
                .id(postId)
                .creatorId(7L)
                .status("draft")
                .build();
    }
}
