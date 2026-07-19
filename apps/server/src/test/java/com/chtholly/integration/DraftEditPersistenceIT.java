package com.chtholly.integration;

import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.skill.SkillOutputValidator;
import com.chtholly.agent.skill.SkillRegistry;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.post.draftedit.DraftEditPreview;
import com.chtholly.post.draftedit.DraftEditPreviewMapper;
import com.chtholly.post.draftedit.DraftEditService;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.post.service.impl.PostCacheInvalidator;
import com.chtholly.post.service.PostService;
import com.chtholly.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** MySQL contracts for preview state transitions and optimistic draft application. */
class DraftEditPersistenceIT extends AbstractGoldenPathIT {

    private static final long OWNER_ID = 701L;

    @Autowired private PostService postService;
    @Autowired private PostMapper postMapper;
    @Autowired private DraftEditPreviewMapper previewMapper;
    @Autowired private StorageService storage;
    @Autowired private PostCacheInvalidator cacheInvalidator;
    @Autowired private TransactionOperations transactions;

    @BeforeEach
    void setUpData() {
        cleanDatabase();
        jdbc.update("INSERT INTO users (id, nickname, handle) VALUES (?, ?, ?)",
                OWNER_ID, "Draft Owner", "draft-owner");
    }

    @Test
    void previewDecisionAndDraftVersionAdvanceAtMostOnce() {
        long draftId = postService.createDraft(OWNER_ID);
        String baseSha = "a".repeat(64);
        jdbc.update("UPDATE posts SET content_sha256 = ? WHERE id = ?", baseSha, draftId);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        DraftEditPreview preview = DraftEditPreview.builder()
                .id(9001L)
                .ownerId(OWNER_ID)
                .draftId(draftId)
                .skillId("draft-edit")
                .skillVersion("v1")
                .baseContentSha256(baseSha)
                .candidateContent("# candidate")
                .candidateContentSha256("b".repeat(64))
                .previewHash("c".repeat(64))
                .status(DraftEditPreview.PENDING)
                .createdAt(now)
                .expiresAt(now.plusSeconds(600))
                .build();
        previewMapper.insert(preview);

        Post update = Post.builder()
                .id(draftId)
                .creatorId(OWNER_ID)
                .contentUrl("/candidate.md")
                .contentObjectKey("posts/" + draftId + "/candidate.md")
                .contentEtag(null)
                .contentSize(11L)
                .contentSha256("b".repeat(64))
                .updateTime(now)
                .build();

        assertThat(postMapper.applyDraftEdit(update, baseSha)).isEqualTo(1);
        assertThat(postMapper.applyDraftEdit(update, baseSha)).isZero();
        assertThat(previewMapper.markApplied(9001L, now)).isEqualTo(1);
        assertThat(previewMapper.markApplied(9001L, now)).isZero();
        assertThat(previewMapper.findById(9001L).getStatus()).isEqualTo(DraftEditPreview.APPLIED);
        assertThat(postMapper.findById(draftId).getContentSha256()).isEqualTo("b".repeat(64));
        assertThat(postMapper.findById(draftId).getContentEtag()).isNull();
    }

    @Test
    void concurrentConfirmationsConvergeToOneAppliedDraftVersion() throws Exception {
        long draftId = createDraftWithBase("a".repeat(64));
        DraftEditPreview preview = preview(9002L, draftId, "a".repeat(64), "# concurrent candidate");
        previewMapper.insert(preview);
        DraftEditService service = service(previewMapper);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<DraftEditService.DecisionResult> first = executor.submit(() -> {
                start.await();
                return service.confirm(OWNER_ID, draftId, preview.getId(), preview.getPreviewHash());
            });
            Future<DraftEditService.DecisionResult> second = executor.submit(() -> {
                start.await();
                return service.confirm(OWNER_ID, draftId, preview.getId(), preview.getPreviewHash());
            });
            start.countDown();

            assertThat(first.get(20, TimeUnit.SECONDS).status()).isEqualTo(DraftEditPreview.APPLIED);
            assertThat(second.get(20, TimeUnit.SECONDS).status()).isEqualTo(DraftEditPreview.APPLIED);
            assertThat(previewMapper.findById(preview.getId()).getStatus()).isEqualTo(DraftEditPreview.APPLIED);
            assertThat(postMapper.findById(draftId).getContentSha256())
                    .isEqualTo(preview.getCandidateContentSha256());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void postAdvanceRollsBackWhenPreviewCannotReachApplied() {
        String baseSha = "d".repeat(64);
        long draftId = createDraftWithBase(baseSha);
        DraftEditPreview preview = preview(9003L, draftId, baseSha, "# rollback candidate");
        previewMapper.insert(preview);
        DraftEditPreviewMapper failingPreviewMapper = mock(DraftEditPreviewMapper.class);
        when(failingPreviewMapper.findByIdForUpdate(preview.getId())).thenReturn(preview);
        when(failingPreviewMapper.markApplied(anyLong(), any(Instant.class))).thenReturn(0);

        assertThatThrownBy(() -> service(failingPreviewMapper)
                .confirm(OWNER_ID, draftId, preview.getId(), preview.getPreviewHash()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("状态不一致");

        assertThat(postMapper.findById(draftId).getContentSha256()).isEqualTo(baseSha);
        assertThat(previewMapper.findById(preview.getId()).getStatus()).isEqualTo(DraftEditPreview.PENDING);
    }

    private long createDraftWithBase(String baseSha) {
        long draftId = postService.createDraft(OWNER_ID);
        jdbc.update("UPDATE posts SET content_sha256 = ? WHERE id = ?", baseSha, draftId);
        return draftId;
    }

    private DraftEditPreview preview(long id, long draftId, String baseSha, String candidate) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        DraftEditPreview preview = DraftEditPreview.builder()
                .id(id)
                .ownerId(OWNER_ID)
                .draftId(draftId)
                .skillId("draft-edit")
                .skillVersion("v1")
                .baseContentSha256(baseSha)
                .candidateContent(candidate)
                .candidateContentSha256(sha256(candidate))
                .status(DraftEditPreview.PENDING)
                .createdAt(now)
                .expiresAt(now.plusSeconds(600))
                .build();
        preview.setPreviewHash(sha256(String.join("\n", List.of(
                String.valueOf(preview.getId()),
                String.valueOf(preview.getOwnerId()),
                String.valueOf(preview.getDraftId()),
                preview.getSkillId(),
                preview.getSkillVersion(),
                preview.getBaseContentSha256(),
                preview.getCandidateContentSha256(),
                String.valueOf(preview.getExpiresAt().toEpochMilli())))));
        return preview;
    }

    private DraftEditService service(DraftEditPreviewMapper previews) {
        return new DraftEditService(
                postMapper,
                previews,
                mock(SkillRegistry.class),
                new SkillOutputValidator(),
                mock(AgentLlmInvoker.class),
                storage,
                new SnowflakeIdGenerator(),
                cacheInvalidator,
                transactions,
                objectMapper);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
