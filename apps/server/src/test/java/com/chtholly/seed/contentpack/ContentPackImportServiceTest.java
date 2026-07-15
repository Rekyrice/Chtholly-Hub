package com.chtholly.seed.contentpack;

import com.chtholly.counter.service.UserCounterService;
import com.chtholly.post.service.impl.PostCacheInvalidator;
import com.chtholly.search.index.SearchIndexService;
import com.chtholly.seed.contentpack.ContentPackDatabaseWriter.ResolvedIdentities;
import com.chtholly.seed.contentpack.ContentPackDatabaseWriter.WriteResult;
import com.chtholly.seed.contentpack.ContentPackMediaPublisher.PublishedContent;
import com.chtholly.seed.contentpack.ContentPackReactionApplier.ReactionApplyResult;
import com.chtholly.seed.contentpack.ContentPackSnapshotWriter.SnapshotRef;
import com.chtholly.seed.contentpack.model.ContentPack;
import com.chtholly.seed.contentpack.model.ContentPackImportReport;
import com.chtholly.seed.contentpack.model.ContentPackManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class ContentPackImportServiceTest {

    @TempDir Path root;
    private final ContentPackLoader loader = mock(ContentPackLoader.class);
    private final ContentPackValidator validator = mock(ContentPackValidator.class);
    private final ContentPackQualityGate qualityGate = mock(ContentPackQualityGate.class);
    private final ContentPackSnapshotWriter snapshotWriter = mock(ContentPackSnapshotWriter.class);
    private final ContentPackMediaPublisher mediaPublisher = mock(ContentPackMediaPublisher.class);
    private final ContentPackDatabaseWriter databaseWriter = mock(ContentPackDatabaseWriter.class);
    private final ContentPackReactionApplier reactionApplier = mock(ContentPackReactionApplier.class);
    private final UserCounterService userCounterService = mock(UserCounterService.class);
    private final PostCacheInvalidator cacheInvalidator = mock(PostCacheInvalidator.class);
    private final SearchIndexService searchIndexService = mock(SearchIndexService.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private ContentPackImportService service;
    private ContentPack pack;
    private PublishedContent published;

    @BeforeEach
    void setUp() throws Exception {
        service = new ContentPackImportService(loader, validator, qualityGate, snapshotWriter,
                mediaPublisher, databaseWriter, reactionApplier, userCounterService,
                cacheInvalidator, searchIndexService, redisson);
        pack = pack("complete");
        published = new PublishedContent(Map.of(), Map.of(), List.of(), "seed-content-v2", "lease-1");
        when(loader.load(root)).thenReturn(pack);
        when(validator.validate(any())).thenReturn(new ContentPackValidator.ValidationResult(List.of()));
        when(qualityGate.audit(any())).thenReturn(new ContentPackQualityGate.QualityGateResult(List.of(), List.of()));
        when(redisson.getLock("lock:seed-content-import:seed-content-v2")).thenReturn(lock);
        when(lock.tryLock(30, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(snapshotWriter.write(any(), any())).thenReturn(new SnapshotRef(root, List.of("accounts.json")));
        when(mediaPublisher.publishAll(pack)).thenReturn(published);
        when(databaseWriter.write(pack, published)).thenReturn(writeResult(List.of(11L, 12L), Map.of(101L, 2)));
        when(reactionApplier.apply(any(), any(), any())).thenReturn(new ReactionApplyResult(false, List.of()));
        when(searchIndexService.tryUpsertPost(anyLong())).thenReturn(true);
    }

    @Test
    void given_dryRun_when_import_then_validatesWithoutSnapshotLockMediaOrDatabaseWrites() {
        ContentPackImportReport report = service.run(root, true);

        assertThat(report.status()).isEqualTo("validated");
        verify(databaseWriter).validateExternalPostReferences(pack);
        verifyNoInteractions(snapshotWriter, mediaPublisher, reactionApplier,
                userCounterService, cacheInvalidator, searchIndexService, redisson);
    }

    @Test
    void givenInvalidExternalPostReference_whenDryRun_thenFailsBeforeSnapshotAndMedia() {
        doThrow(new IllegalArgumentException("missing site-owner public post: missing"))
                .when(databaseWriter).validateExternalPostReferences(pack);

        ContentPackImportReport report = service.run(root, true);

        assertThat(report.status()).isEqualTo("failed");
        assertThat(report.failedStage()).isEqualTo("external-target");
        verifyNoInteractions(snapshotWriter, mediaPublisher, reactionApplier, redisson);
    }

    @Test
    void given_reviewStage_when_dryRun_then_validatesWithoutTakingInfrastructureLock() {
        when(loader.load(root)).thenReturn(pack("review"));

        ContentPackImportReport report = service.run(root, true);

        assertThat(report.status()).isEqualTo("validated");
        verify(databaseWriter).validateExternalPostReferences(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(redisson, snapshotWriter, mediaPublisher);
    }

    @Test
    void given_qualityErrors_when_import_then_stopsBeforeSnapshotAndMedia() {
        when(qualityGate.audit(any())).thenReturn(
                new ContentPackQualityGate.QualityGateResult(List.of("duplicate lead"), List.of()));

        ContentPackImportReport report = service.run(root, false);

        assertThat(report.status()).isEqualTo("failed");
        assertThat(report.failedStage()).isEqualTo("quality");
        assertThat(report.qualityErrors()).containsExactly("duplicate lead");
        verifyNoInteractions(snapshotWriter, mediaPublisher, databaseWriter, redisson);
    }

    @Test
    void given_reviewStage_when_formalImport_then_failsBeforeSnapshotAndMedia() {
        when(loader.load(root)).thenReturn(pack("review"));

        ContentPackImportReport report = service.run(root, false);

        assertThat(report.status()).isEqualTo("failed");
        assertThat(report.failedStage()).isEqualTo("manifest-stage");
        verify(databaseWriter).validateExternalPostReferences(org.mockito.ArgumentMatchers.any());
        verify(databaseWriter, never()).write(any(), any());
        verifyNoInteractions(snapshotWriter, mediaPublisher);
    }

    @Test
    void given_validationFailure_when_import_then_stopsBeforeQualityAndMedia() {
        when(validator.validate(pack)).thenThrow(new IllegalArgumentException("bad pack"));

        ContentPackImportReport report = service.run(root, false);

        assertThat(report.status()).isEqualTo("failed");
        assertThat(report.failedStage()).isEqualTo("validation");
        verifyNoInteractions(qualityGate, snapshotWriter, mediaPublisher, databaseWriter);
    }

    @Test
    void given_mediaFailure_when_import_then_stopsBeforeDatabase() throws Exception {
        when(mediaPublisher.publishAll(pack)).thenThrow(new IllegalStateException("OSS unavailable"));

        ContentPackImportReport report = service.run(root, false);

        assertThat(report.status()).isEqualTo("failed");
        assertThat(report.failedStage()).isEqualTo("media");
        verify(databaseWriter).validateExternalPostReferences(pack);
        verify(databaseWriter, never()).write(any(), any());
    }

    @Test
    void given_snapshotFailure_when_import_then_stopsBeforeMedia() {
        when(snapshotWriter.write(any(), any())).thenThrow(new IllegalStateException("disk full"));

        ContentPackImportReport report = service.run(root, false);

        assertThat(report.status()).isEqualTo("failed");
        assertThat(report.failedStage()).isEqualTo("snapshot");
        verify(databaseWriter).validateExternalPostReferences(pack);
        verify(databaseWriter, never()).write(any(), any());
        verifyNoInteractions(mediaPublisher);
        verify(lock).unlock();
    }

    @Test
    void given_databaseFailure_when_import_then_rollsBackOwnedMediaExactlyOnce() {
        when(databaseWriter.write(pack, published)).thenThrow(new IllegalStateException("DB unavailable"));

        ContentPackImportReport report = service.run(root, false);

        assertThat(report.status()).isEqualTo("failed");
        assertThat(report.failedStage()).isEqualTo("database");
        verify(mediaPublisher).rollbackNewObjects(published);
        verify(mediaPublisher, never()).commitPublishedObjects(published);
    }

    @Test
    void given_databaseSuccess_when_import_then_commitsPublicationBeforeRuntimeWorkExactlyOnce() {
        service.run(root, false);

        var order = inOrder(databaseWriter, mediaPublisher, reactionApplier);
        order.verify(databaseWriter).write(pack, published);
        order.verify(mediaPublisher).commitPublishedObjects(published);
        order.verify(reactionApplier).apply(pack.reactions(), pack.views(), writeResult(List.of(11L, 12L), Map.of(101L, 2)).identities());
        verify(mediaPublisher, never()).rollbackNewObjects(published);
    }

    @Test
    void given_publicationCommitFailure_when_databaseCommitted_then_recoversLeaseAndContinuesAllReconciliation() {
        doThrow(new IllegalStateException("release interrupted"))
                .when(mediaPublisher).commitPublishedObjects(published);

        ContentPackImportReport report = service.run(root, false);

        assertThat(report.status()).isEqualTo("partial");
        assertThat(report.failedStage()).isEqualTo("media-commit");
        verify(mediaPublisher).ensurePublicationLeaseReleased(published);
        verify(reactionApplier).apply(pack.reactions(), pack.views(), writeResult(
                List.of(11L, 12L), Map.of(101L, 2)).identities());
        verify(userCounterService).rebuildAllCounters(101L);
        verify(cacheInvalidator).invalidate(11L);
        verify(cacheInvalidator).invalidate(12L);
        verify(searchIndexService).tryUpsertPost(11L);
        verify(searchIndexService).tryUpsertPost(12L);
        verify(mediaPublisher, never()).rollbackNewObjects(published);
    }

    @Test
    void given_runtimeFailure_when_databaseCommitted_then_reportsPartialAndStillInvalidatesAndIndexes() {
        doThrow(new IllegalStateException("counter down")).when(userCounterService).incrementPosts(101L, 2);

        ContentPackImportReport report = service.run(root, false);

        assertThat(report.status()).isEqualTo("partial");
        assertThat(report.failedStage()).isEqualTo("runtime-state");
        verify(cacheInvalidator).invalidate(11L);
        verify(cacheInvalidator).invalidate(12L);
        verify(searchIndexService).tryUpsertPost(11L);
        verify(searchIndexService).tryUpsertPost(12L);
        verify(userCounterService).rebuildAllCounters(101L);
    }

    @Test
    void given_indexFailure_when_databaseCommitted_then_reportsEveryFailedIdWithoutStoppingOthers() {
        when(searchIndexService.tryUpsertPost(11L)).thenReturn(false);
        when(searchIndexService.tryUpsertPost(12L)).thenReturn(true);

        ContentPackImportReport report = service.run(root, false);

        assertThat(report.status()).isEqualTo("partial");
        assertThat(report.failedStage()).isEqualTo("search-index");
        assertThat(report.indexFailures()).containsExactly(11L);
        verify(searchIndexService).tryUpsertPost(12L);
    }

    @Test
    void given_unchangedPosts_when_import_then_reconcilesEveryResolvedPostAndAuthor() {
        when(databaseWriter.write(pack, published)).thenReturn(writeResult(List.of(), Map.of()));

        ContentPackImportReport report = service.run(root, false);

        assertThat(report.status()).isEqualTo("completed");
        assertThat(report.attemptedPostIds()).containsExactly(11L, 12L);
        verify(cacheInvalidator).invalidate(11L);
        verify(cacheInvalidator).invalidate(12L);
        verify(searchIndexService).tryUpsertPost(11L);
        verify(searchIndexService).tryUpsertPost(12L);
        verify(userCounterService).rebuildAllCounters(101L);
        verify(userCounterService, never()).incrementPosts(anyLong(), anyInt());
    }

    @Test
    void given_firstPostCommitFailures_when_unchangedRetry_then_reconcilesAgainWithoutDuplicateIncrement() {
        WriteResult first = writeResult(List.of(11L, 12L), Map.of(101L, 2));
        WriteResult retry = writeResult(List.of(), Map.of());
        when(databaseWriter.write(pack, published)).thenReturn(first, retry);
        doThrow(new IllegalStateException("cache down")).doNothing().when(cacheInvalidator).invalidate(11L);
        when(searchIndexService.tryUpsertPost(11L)).thenReturn(false, true);
        doThrow(new IllegalStateException("counter rebuild down")).doNothing()
                .when(userCounterService).rebuildAllCounters(101L);

        ContentPackImportReport firstReport = service.run(root, false);
        ContentPackImportReport retryReport = service.run(root, false);

        assertThat(firstReport.status()).isEqualTo("partial");
        assertThat(retryReport.status()).isEqualTo("completed");
        assertThat(retryReport.attemptedPostIds()).containsExactly(11L, 12L);
        verify(userCounterService).incrementPosts(101L, 2);
        verify(userCounterService, times(2)).rebuildAllCounters(101L);
        verify(cacheInvalidator, times(2)).invalidate(11L);
        verify(cacheInvalidator, times(2)).invalidate(12L);
        verify(searchIndexService, times(2)).tryUpsertPost(11L);
        verify(searchIndexService, times(2)).tryUpsertPost(12L);
    }

    @Test
    void given_pendingViews_when_import_then_reportsPartialAndPendingIds() {
        when(reactionApplier.apply(any(), any(), any())).thenReturn(new ReactionApplyResult(true, List.of(12L)));

        ContentPackImportReport report = service.run(root, false);

        assertThat(report.status()).isEqualTo("partial");
        assertThat(report.failedStage()).isEqualTo("runtime-state");
        assertThat(report.pendingViewPostIds()).containsExactly(12L);
    }

    @Test
    void givenRetiredPosts_whenImport_thenRebuildsAuthorsInvalidatesCachesDeletesIndexesAndReportsOutcome() {
        when(databaseWriter.write(pack, published)).thenReturn(writeResult(
                List.of(11L), Map.of(), List.of(91L, 92L), List.of(201L), List.of("missing-old-post")));

        ContentPackImportReport report = service.run(root, false);

        assertThat(report.status()).isEqualTo("completed");
        assertThat(report.retiredPostIds()).containsExactly(91L, 92L);
        assertThat(report.unmatchedRetirementSlugs()).containsExactly("missing-old-post");
        verify(userCounterService).rebuildAllCounters(101L);
        verify(userCounterService).rebuildAllCounters(201L);
        verify(cacheInvalidator).invalidate(91L);
        verify(cacheInvalidator).invalidate(92L);
        verify(searchIndexService).softDeletePost(91L);
        verify(searchIndexService).softDeletePost(92L);
        verify(searchIndexService, never()).tryUpsertPost(91L);
        verify(searchIndexService, never()).tryUpsertPost(92L);
    }

    private ContentPack pack(String stage) {
        return new ContentPack(root, new ContentPackManifest(
                "2.0.0", "seed-content-v2", stage, 0, 0, Map.of()),
                List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private WriteResult writeResult(List<Long> changed, Map<Long, Integer> created) {
        return writeResult(changed, created, List.of(), List.of(), List.of());
    }

    private WriteResult writeResult(
            List<Long> changed,
            Map<Long, Integer> created,
            List<Long> retiredPostIds,
            List<Long> retiredAuthorIds,
            List<String> unmatchedRetirementSlugs) {
        Map<String, Long> postIds = new java.util.LinkedHashMap<>();
        postIds.put("post-a", 11L);
        postIds.put("post-b", 12L);
        return new WriteResult(new ResolvedIdentities(
                "seed-content-v2", Map.of("author", 101L), postIds), changed, created,
                retiredPostIds, retiredAuthorIds, unmatchedRetirementSlugs);
    }
}
