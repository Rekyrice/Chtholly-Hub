package com.chtholly.post.draftedit;

import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.skill.SkillDefinition;
import com.chtholly.agent.skill.SkillOutputValidator;
import com.chtholly.agent.skill.SkillRegistry;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.post.service.impl.PostCacheInvalidator;
import com.chtholly.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DraftEditServiceTest {

    @Mock private PostMapper postMapper;
    @Mock private DraftEditPreviewMapper previewMapper;
    @Mock private SkillRegistry registry;
    @Mock private AgentLlmInvoker llm;
    @Mock private StorageService storage;
    @Mock private SnowflakeIdGenerator ids;
    @Mock private PostCacheInvalidator cacheInvalidator;
    @Mock private TransactionOperations transactions;
    @Mock private AgentObservationService observationService;
    @Mock private Observation previewSpan;
    @Mock private Observation applySpan;

    private DraftEditService service;

    @BeforeEach
    void setUp() {
        service = new DraftEditService(
                postMapper, previewMapper, registry, new SkillOutputValidator(), llm, storage,
                ids, cacheInvalidator, transactions, new ObjectMapper(), observationService);
    }

    @Test
    void ownershipAndBaseHashAreValidatedBeforeModelInvocation() {
        when(observationService.startDraftPreviewSpan("v1")).thenReturn(previewSpan);
        when(registry.enabled()).thenReturn(List.of(definition()));
        String base = "# draft";
        String baseSha = DraftEditService.sha256(base);
        when(postMapper.findById(42L)).thenReturn(draft(8L, baseSha));

        assertThatThrownBy(() -> service.createPreview(7L, 42L, base, baseSha, "润色"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verifyNoInteractions(llm, previewMapper);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> low = ArgumentCaptor.forClass(Map.class);
        verify(observationService).finishSpanError(
                eq(previewSpan), eq("draft_preview_failed"), low.capture(), eq(Map.of()));
        assertThat(low.getValue())
                .containsEntry("status", "permission_denied")
                .containsEntry("error.type", "PERMISSION_DENIED");
    }

    @Test
    void structuredCandidateIsPersistedWithPinnedSkillVersionAndHashes() throws Exception {
        when(observationService.startDraftPreviewSpan("v1")).thenReturn(previewSpan);
        when(registry.enabled()).thenReturn(List.of(definition()));
        String base = "# draft";
        String candidate = "# polished\n\ncontent";
        String baseSha = DraftEditService.sha256(base);
        when(postMapper.findById(42L)).thenReturn(draft(7L, baseSha));
        when(ids.nextId()).thenReturn(99L);
        when(llm.call(anyString(), anyString(), eq(0.1), anyInt()))
                .thenReturn(new ObjectMapper().writeValueAsString(Map.of("candidateContent", candidate)));

        DraftEditService.PreviewResult result =
                service.createPreview(7L, 42L, base, baseSha, "润色并保留事实");

        ArgumentCaptor<DraftEditPreview> captor = ArgumentCaptor.forClass(DraftEditPreview.class);
        verify(previewMapper).insert(captor.capture());
        DraftEditPreview saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(99L);
        assertThat(saved.getSkillId()).isEqualTo("draft-edit");
        assertThat(saved.getSkillVersion()).isEqualTo("v1");
        assertThat(saved.getStatus()).isEqualTo(DraftEditPreview.PENDING);
        assertThat(saved.getBaseContentSha256()).isEqualTo(baseSha);
        assertThat(saved.getCandidateContentSha256()).isEqualTo(DraftEditService.sha256(candidate));
        assertThat(saved.getPreviewHash()).hasSize(64);
        assertThat(saved.getCreatedAt().getNano() % 1_000_000).isZero();
        assertThat(saved.getExpiresAt().getNano() % 1_000_000).isZero();
        assertThat(result.previewId()).isEqualTo("99");
        assertThat(result.candidateContent()).isEqualTo(candidate);
        assertThat(result.previewHash()).isEqualTo(saved.getPreviewHash());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> low = ArgumentCaptor.forClass(Map.class);
        verify(observationService).finishSpan(eq(previewSpan), low.capture(), eq(Map.of()));
        assertThat(low.getValue()).containsEntry("status", "pending");
    }

    @Test
    void tamperedPreviewHashIsRejectedBeforeStorageWrite() {
        executeTransactionsImmediately();
        DraftEditPreview preview = pendingPreview();
        when(previewMapper.findByIdForUpdate(99L)).thenReturn(preview);

        assertThatThrownBy(() -> service.confirm(7L, 42L, 99L, "tampered"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        verifyNoInteractions(storage);
    }

    @Test
    void staleBaseVersionCannotOverwriteNewerDraft() throws Exception {
        when(observationService.startDraftApplySpan("v1")).thenReturn(applySpan);
        executeTransactionsImmediately();
        DraftEditPreview preview = pendingPreview();
        when(previewMapper.findByIdForUpdate(99L)).thenReturn(preview);
        when(storage.objectExists(anyString())).thenReturn(false);
        when(postMapper.findDraftByIdForUpdate(42L))
                .thenReturn(draft(7L, DraftEditService.sha256("newer")));

        assertThatThrownBy(() -> service.confirm(7L, 42L, 99L, preview.getPreviewHash()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(postMapper, never()).applyDraftEdit(any(Post.class), anyString());
        verify(previewMapper, never()).markApplied(eq(99L), any(Instant.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> low = ArgumentCaptor.forClass(Map.class);
        verify(observationService).finishSpanError(
                eq(applySpan), eq("draft_apply_failed"), low.capture(), eq(Map.of()));
        assertThat(low.getValue())
                .containsEntry("status", "version_conflict")
                .containsEntry("error.type", "DRAFT_VERSION_CONFLICT");
    }

    @Test
    void confirmationWritesImmutableObjectAndAppliesExactlyOnce() throws Exception {
        when(observationService.startDraftApplySpan("v1")).thenReturn(applySpan);
        executeTransactionsImmediately();
        DraftEditPreview preview = pendingPreview();
        when(previewMapper.findByIdForUpdate(99L)).thenReturn(preview);
        when(storage.objectExists(anyString())).thenReturn(false);
        when(storage.resolvePublicUrl(anyString())).thenReturn("/content/edited.md");
        when(postMapper.findDraftByIdForUpdate(42L)).thenReturn(draft(7L, preview.getBaseContentSha256()));
        when(postMapper.applyDraftEdit(any(Post.class), eq(preview.getBaseContentSha256()))).thenReturn(1);
        when(previewMapper.markApplied(eq(99L), any(Instant.class))).thenReturn(1);

        DraftEditService.DecisionResult result =
                service.confirm(7L, 42L, 99L, preview.getPreviewHash());

        String expectedKey = "posts/42/content-edits/" + preview.getCandidateContentSha256() + ".md";
        verify(storage).uploadVerifiedObject(
                eq(expectedKey), any(), eq("text/markdown; charset=utf-8"),
                eq((long) preview.getCandidateContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
                eq(preview.getCandidateContentSha256()));
        ArgumentCaptor<Post> post = ArgumentCaptor.forClass(Post.class);
        verify(postMapper).applyDraftEdit(post.capture(), eq(preview.getBaseContentSha256()));
        assertThat(post.getValue().getContentObjectKey()).isEqualTo(expectedKey);
        assertThat(post.getValue().getContentSha256()).isEqualTo(preview.getCandidateContentSha256());
        verify(cacheInvalidator).invalidate(42L);
        assertThat(result.status()).isEqualTo(DraftEditPreview.APPLIED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> low = ArgumentCaptor.forClass(Map.class);
        verify(observationService).finishSpan(eq(applySpan), low.capture(), eq(Map.of()));
        assertThat(low.getValue()).containsEntry("status", "applied");
    }

    @Test
    void alreadyAppliedConfirmationIsIdempotent() throws Exception {
        when(observationService.startDraftApplySpan("v1")).thenReturn(applySpan);
        executeTransactionsImmediately();
        DraftEditPreview applied = pendingPreview();
        applied.setStatus(DraftEditPreview.APPLIED);
        when(previewMapper.findByIdForUpdate(99L)).thenReturn(applied);
        when(storage.objectExists(anyString())).thenReturn(true);
        when(storage.objectMatches(anyString(), eq(applied.getCandidateContentSha256()), anyLong()))
                .thenReturn(true);
        when(storage.resolvePublicUrl(anyString())).thenReturn("/content/edited.md");

        DraftEditService.DecisionResult result =
                service.confirm(7L, 42L, 99L, applied.getPreviewHash());

        assertThat(result.status()).isEqualTo(DraftEditPreview.APPLIED);
        verify(storage).uploadVerifiedObject(
                anyString(), any(), anyString(), anyLong(), eq(applied.getCandidateContentSha256()));
        verify(postMapper, never()).applyDraftEdit(any(Post.class), anyString());
        verify(cacheInvalidator).invalidate(42L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> low = ArgumentCaptor.forClass(Map.class);
        verify(observationService).finishSpan(eq(applySpan), low.capture(), eq(Map.of()));
        assertThat(low.getValue()).containsEntry("status", "idempotent_hit");
    }

    @Test
    void rejectedPreviewCanNeverBeApplied() {
        executeTransactionsImmediately();
        DraftEditPreview rejected = pendingPreview();
        rejected.setStatus(DraftEditPreview.REJECTED);
        when(previewMapper.findByIdForUpdate(99L)).thenReturn(rejected);

        assertThatThrownBy(() -> service.confirm(7L, 42L, 99L, rejected.getPreviewHash()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        verifyNoInteractions(storage);
    }

    @Test
    void explicitRejectEndsPendingPreviewWithoutWritingContent() {
        when(observationService.startDraftApplySpan("v1")).thenReturn(applySpan);
        executeTransactionsImmediately();
        DraftEditPreview preview = pendingPreview();
        when(previewMapper.findByIdForUpdate(99L)).thenReturn(preview);
        when(previewMapper.markRejected(eq(99L), any(Instant.class))).thenReturn(1);

        DraftEditService.DecisionResult result =
                service.reject(7L, 42L, 99L, preview.getPreviewHash());

        assertThat(result.status()).isEqualTo(DraftEditPreview.REJECTED);
        verify(previewMapper).markRejected(eq(99L), any(Instant.class));
        verifyNoInteractions(storage);
        verify(postMapper, never()).applyDraftEdit(any(Post.class), anyString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> low = ArgumentCaptor.forClass(Map.class);
        verify(observationService).finishSpan(eq(applySpan), low.capture(), eq(Map.of()));
        assertThat(low.getValue()).containsEntry("status", "rejected");
    }

    @Test
    void expiredPreviewIsPersistentlyClosedBeforeAnyStorageWrite() {
        when(observationService.startDraftApplySpan("v1")).thenReturn(applySpan);
        executeTransactionsImmediately();
        DraftEditPreview preview = pendingPreview();
        preview.setExpiresAt(Instant.now().minusSeconds(1));
        preview.setPreviewHash(DraftEditService.previewHash(preview));
        when(previewMapper.findByIdForUpdate(99L)).thenReturn(preview);
        when(previewMapper.markExpired(eq(99L), any(Instant.class))).thenReturn(1);

        assertThatThrownBy(() -> service.confirm(7L, 42L, 99L, preview.getPreviewHash()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(previewMapper).markExpired(eq(99L), any(Instant.class));
        verifyNoInteractions(storage);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> low = ArgumentCaptor.forClass(Map.class);
        verify(observationService).finishSpanError(
                eq(applySpan), eq("draft_apply_failed"), low.capture(), eq(Map.of()));
        assertThat(low.getValue())
                .containsEntry("status", "expired")
                .containsEntry("error.type", "DRAFT_VERSION_CONFLICT");
    }

    @Test
    void corruptedPersistedCandidateIsRejectedBeforeAnyStorageWrite() {
        executeTransactionsImmediately();
        DraftEditPreview preview = pendingPreview();
        preview.setCandidateContent("# tampered");
        when(previewMapper.findByIdForUpdate(99L)).thenReturn(preview);

        assertThatThrownBy(() -> service.confirm(7L, 42L, 99L, preview.getPreviewHash()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        verifyNoInteractions(storage);
        verify(postMapper, never()).applyDraftEdit(any(Post.class), anyString());
    }

    @Test
    void matchingExistingObjectIsReassertedForRecoverableStoragePolicy() throws Exception {
        executeTransactionsImmediately();
        DraftEditPreview preview = pendingPreview();
        when(previewMapper.findByIdForUpdate(99L)).thenReturn(preview);
        when(storage.objectExists(anyString())).thenReturn(true);
        when(storage.objectMatches(anyString(), eq(preview.getCandidateContentSha256()), anyLong()))
                .thenReturn(true);
        when(storage.resolvePublicUrl(anyString())).thenReturn("/content/edited.md");
        when(postMapper.findDraftByIdForUpdate(42L)).thenReturn(draft(7L, preview.getBaseContentSha256()));
        when(postMapper.applyDraftEdit(any(Post.class), eq(preview.getBaseContentSha256()))).thenReturn(1);
        when(previewMapper.markApplied(eq(99L), any(Instant.class))).thenReturn(1);

        service.confirm(7L, 42L, 99L, preview.getPreviewHash());

        verify(storage).uploadVerifiedObject(
                anyString(), any(), eq("text/markdown; charset=utf-8"), anyLong(),
                eq(preview.getCandidateContentSha256()));
    }

    private SkillDefinition definition() {
        return new SkillDefinition(
                "draft-edit", "v1", true, "draft edit", List.of("draft_edit"),
                List.of("DRAFT_CONTENT", "USER_REQUEST"), List.of(), "Return JSON only.",
                Map.of(), Map.of("type", "MARKDOWN_DRAFT", "maxChars", 200_000),
                List.of("draft-content", "length"), "CONTROLLED_WRITE", "EXPLICIT_CONFIRMATION",
                45_000, 1, "draft-edit-v1");
    }

    private void executeTransactionsImmediately() {
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private Post draft(long ownerId, String sha256) {
        return Post.builder()
                .id(42L)
                .creatorId(ownerId)
                .status("draft")
                .contentSha256(sha256)
                .build();
    }

    private DraftEditPreview pendingPreview() {
        String baseSha = DraftEditService.sha256("# draft");
        String candidate = "# polished\n\ncontent";
        String candidateSha = DraftEditService.sha256(candidate);
        DraftEditPreview preview = DraftEditPreview.builder()
                .id(99L)
                .ownerId(7L)
                .draftId(42L)
                .skillId("draft-edit")
                .skillVersion("v1")
                .baseContentSha256(baseSha)
                .candidateContent(candidate)
                .candidateContentSha256(candidateSha)
                .status(DraftEditPreview.PENDING)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        preview.setPreviewHash(DraftEditService.previewHash(preview));
        return preview;
    }
}
