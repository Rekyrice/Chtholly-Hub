package com.chtholly.seed.contentpack;

import com.chtholly.counter.service.UserCounterService;
import com.chtholly.post.service.impl.PostCacheInvalidator;
import com.chtholly.relation.service.impl.RelationCacheInvalidator;
import com.chtholly.search.index.SearchIndexService;
import com.chtholly.seed.contentpack.ContentPackDatabaseWriter.WriteResult;
import com.chtholly.seed.contentpack.ContentPackMediaPublisher.PublishedContent;
import com.chtholly.seed.contentpack.ContentPackQualityGate.QualityGateResult;
import com.chtholly.seed.contentpack.ContentPackReactionApplier.ReactionApplyResult;
import com.chtholly.seed.contentpack.ContentPackSnapshotWriter.SnapshotRef;
import com.chtholly.seed.contentpack.ContentPackValidator.ValidationResult;
import com.chtholly.seed.contentpack.model.ContentPack;
import com.chtholly.seed.contentpack.model.ContentPackImportReport;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates validation and idempotent formal content-pack imports across storage boundaries.
 *
 * <p>The MySQL commit is the irreversible boundary. Before it, failures release newly owned media;
 * after it, failures are reported as partial and never roll back accepted relational or media data.
 */
@Component
public final class ContentPackImportService {

    private static final Logger log = LoggerFactory.getLogger(ContentPackImportService.class);
    private static final long LOCK_WAIT_SECONDS = 30L;

    private final ContentPackLoader loader;
    private final ContentPackValidator validator;
    private final ContentPackQualityGate qualityGate;
    private final ContentPackSnapshotWriter snapshotWriter;
    private final ContentPackMediaPublisher mediaPublisher;
    private final ContentPackDatabaseWriter databaseWriter;
    private final ContentPackReactionApplier reactionApplier;
    private final UserCounterService userCounterService;
    private final RelationCacheInvalidator relationCacheInvalidator;
    private final PostCacheInvalidator cacheInvalidator;
    private final SearchIndexService searchIndexService;
    private final RedissonClient redisson;
    private final Clock clock;

    /** Creates an importer using the system UTC clock for collision-resistant run identifiers. */
    @Autowired
    public ContentPackImportService(
            ContentPackLoader loader,
            ContentPackValidator validator,
            ContentPackQualityGate qualityGate,
            ContentPackSnapshotWriter snapshotWriter,
            ContentPackMediaPublisher mediaPublisher,
            ContentPackDatabaseWriter databaseWriter,
            ContentPackReactionApplier reactionApplier,
            UserCounterService userCounterService,
            RelationCacheInvalidator relationCacheInvalidator,
            PostCacheInvalidator cacheInvalidator,
            SearchIndexService searchIndexService,
            RedissonClient redisson) {
        this(loader, validator, qualityGate, snapshotWriter, mediaPublisher, databaseWriter,
                reactionApplier, userCounterService, relationCacheInvalidator,
                cacheInvalidator, searchIndexService, redisson, Clock.systemUTC());
    }

    ContentPackImportService(
            ContentPackLoader loader,
            ContentPackValidator validator,
            ContentPackQualityGate qualityGate,
            ContentPackSnapshotWriter snapshotWriter,
            ContentPackMediaPublisher mediaPublisher,
            ContentPackDatabaseWriter databaseWriter,
            ContentPackReactionApplier reactionApplier,
            UserCounterService userCounterService,
            RelationCacheInvalidator relationCacheInvalidator,
            PostCacheInvalidator cacheInvalidator,
            SearchIndexService searchIndexService,
            RedissonClient redisson,
            Clock clock) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.qualityGate = Objects.requireNonNull(qualityGate, "qualityGate");
        this.snapshotWriter = Objects.requireNonNull(snapshotWriter, "snapshotWriter");
        this.mediaPublisher = Objects.requireNonNull(mediaPublisher, "mediaPublisher");
        this.databaseWriter = Objects.requireNonNull(databaseWriter, "databaseWriter");
        this.reactionApplier = Objects.requireNonNull(reactionApplier, "reactionApplier");
        this.userCounterService = Objects.requireNonNull(userCounterService, "userCounterService");
        this.relationCacheInvalidator = Objects.requireNonNull(
                relationCacheInvalidator, "relationCacheInvalidator");
        this.cacheInvalidator = Objects.requireNonNull(cacheInvalidator, "cacheInvalidator");
        this.searchIndexService = Objects.requireNonNull(searchIndexService, "searchIndexService");
        this.redisson = Objects.requireNonNull(redisson, "redisson");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Validates or formally imports a content pack.
     *
     * @param root versioned content-pack directory
     * @param dryRun whether all mutation boundaries must remain untouched
     * @return redacted structured outcome
     */
    public ContentPackImportReport run(Path root, boolean dryRun) {
        ContentPack pack;
        try {
            pack = loader.load(root);
        } catch (RuntimeException exception) {
            log.warn("Content-pack load failed: {}", exception.getMessage());
            return failed(null, "load", null, null, null);
        }

        ValidationResult validation;
        try {
            validation = validator.validate(pack);
        } catch (RuntimeException exception) {
            log.warn("Content-pack validation failed: {}", exception.getMessage());
            return failed(pack, "validation", null, null, null);
        }

        QualityGateResult quality;
        try {
            quality = qualityGate.audit(pack);
        } catch (RuntimeException exception) {
            log.warn("Content-pack quality audit failed: {}", exception.getMessage());
            return failed(pack, "quality", validation, null, null);
        }
        if (!quality.errors().isEmpty()) {
            return failed(pack, "quality", validation, quality, null);
        }
        try {
            databaseWriter.validateExternalPostReferences(pack);
        } catch (RuntimeException exception) {
            log.warn("Content-pack external target validation failed: {}", exception.getMessage());
            return failed(pack, "external-target", validation, quality, null);
        }
        if (dryRun) {
            return report("validated", null, pack, null, null,
                    List.of(), List.of(), List.of(), validation, quality);
        }
        if (!"complete".equals(pack.manifest().stage())) {
            return failed(pack, "manifest-stage", validation, quality, null);
        }
        return runFormalUnderInfrastructureLock(pack, validation, quality);
    }

    private ContentPackImportReport runFormalUnderInfrastructureLock(
            ContentPack pack, ValidationResult validation, QualityGateResult quality) {
        String namespace = pack.manifest().namespace();
        RLock lock = redisson.getLock("lock:seed-content-import:" + namespace);
        boolean acquired = false;
        try {
            // 跨 JVM 锁覆盖完整正式导入，避免一个进程提交的对象被另一个进程按失败批次清理。
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                return failed(pack, "import-lock", validation, quality, null);
            }
            return runFormal(pack, validation, quality);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failed(pack, "import-lock", validation, quality, null);
        } catch (RuntimeException exception) {
            log.error("Content-pack infrastructure lock failed for namespace {}", namespace, exception);
            return failed(pack, "import-lock", validation, quality, null);
        } finally {
            if (acquired) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (RuntimeException exception) {
                    // 导入结果已经确定，解锁异常只记录；Redisson watchdog 会在连接/进程恢复后释放所有权。
                    log.error("Content-pack import lock release failed for namespace {}", namespace, exception);
                }
            }
        }
    }

    private ContentPackImportReport runFormal(
            ContentPack pack, ValidationResult validation, QualityGateResult quality) {
        SnapshotRef snapshot;
        try {
            snapshot = snapshotWriter.write(pack, newRunId());
        } catch (RuntimeException exception) {
            log.error("Content-pack snapshot failed", exception);
            return failed(pack, "snapshot", validation, quality, null);
        }

        PublishedContent published;
        try {
            published = mediaPublisher.publishAll(pack);
        } catch (Exception exception) {
            log.error("Content-pack media publication failed", exception);
            return failed(pack, "media", validation, quality, snapshot);
        }

        WriteResult write;
        try {
            write = databaseWriter.write(pack, published);
        } catch (RuntimeException databaseFailure) {
            // DB 未提交时，只有本批新建对象可清理；Publisher 在 finally 中释放其 JVM lease。
            try {
                mediaPublisher.rollbackNewObjects(published);
            } catch (RuntimeException rollbackFailure) {
                databaseFailure.addSuppressed(rollbackFailure);
            }
            log.error("Content-pack database write failed", databaseFailure);
            return failed(pack, "database", validation, quality, snapshot);
        }

        try {
            // DB 事务已成功，此处只能接受媒体并释放 lease，后续任何失败都不得再 rollback。
            mediaPublisher.commitPublishedObjects(published);
        } catch (RuntimeException exception) {
            log.error("Content-pack publication commit failed after MySQL commit", exception);
            try {
                mediaPublisher.ensurePublicationLeaseReleased(published);
            } catch (RuntimeException recoveryFailure) {
                exception.addSuppressed(recoveryFailure);
                log.error("Content-pack publication lease recovery failed", recoveryFailure);
            }
            return finishRuntimeState(pack, snapshot, write, validation, quality, true);
        }

        return finishRuntimeState(pack, snapshot, write, validation, quality, false);
    }

    private ContentPackImportReport finishRuntimeState(
            ContentPack pack,
            SnapshotRef snapshot,
            WriteResult write,
            ValidationResult validation,
            QualityGateResult quality,
            boolean publicationCommitFailure) {
        boolean runtimeFailure = false;
        List<Long> pendingViews = List.of();
        try {
            ReactionApplyResult reaction = reactionApplier.apply(
                    pack.reactions(), pack.views(), write.identities());
            pendingViews = reaction.pendingViewPostIds();
            runtimeFailure = reaction.partial();
        } catch (RuntimeException exception) {
            runtimeFailure = true;
            log.error("Content-pack reaction reconciliation failed", exception);
        }

        try {
            relationCacheInvalidator.invalidateUsers(write.affectedFollowUserIds());
        } catch (RuntimeException exception) {
            runtimeFailure = true;
            log.error("Seed relation cache invalidation failed", exception);
        }

        for (var created : write.createdPostCountsByAuthor().entrySet()) {
            try {
                userCounterService.incrementPosts(created.getKey(), created.getValue());
            } catch (RuntimeException exception) {
                runtimeFailure = true;
                log.error("Seed author post counter update failed for user {}", created.getKey(), exception);
            }
        }
        LinkedHashSet<Long> authorIdsToRebuild = new LinkedHashSet<>(write.identities().accountIds().values());
        authorIdsToRebuild.addAll(write.retiredAuthorIds());
        authorIdsToRebuild.addAll(write.affectedFollowUserIds());
        authorIdsToRebuild.addAll(write.affectedReactionPostAuthorIds());
        for (long authorId : authorIdsToRebuild) {
            try {
                // increment 只记录首次创建；随后按 DB 事实重建，使 partial 重跑不会重复累加。
                userCounterService.rebuildAllCounters(authorId);
            } catch (RuntimeException exception) {
                runtimeFailure = true;
                log.error("Seed author counter rebuild failed for user {}", authorId, exception);
            }
        }

        LinkedHashSet<Long> activePostIds = new LinkedHashSet<>(write.postIds());
        activePostIds.addAll(write.identities().externalPostIdsBySlug().values());
        List<Long> attemptedPostIds = List.copyOf(activePostIds);
        LinkedHashSet<Long> postIdsToInvalidate = new LinkedHashSet<>(attemptedPostIds);
        postIdsToInvalidate.addAll(write.retiredPostIds());
        for (long postId : postIdsToInvalidate) {
            try {
                cacheInvalidator.invalidate(postId);
            } catch (RuntimeException exception) {
                runtimeFailure = true;
                log.error("Seed post cache invalidation failed for post {}", postId, exception);
            }
        }

        List<Long> indexFailures = new ArrayList<>();
        for (long postId : attemptedPostIds) {
            try {
                if (!searchIndexService.tryUpsertPost(postId)) {
                    indexFailures.add(postId);
                }
            } catch (RuntimeException exception) {
                // tryUpsertPost normally contains its own failure, but the import report remains complete if a proxy fails.
                indexFailures.add(postId);
                log.error("Seed post indexing invocation failed for post {}", postId, exception);
            }
        }
        for (long postId : write.retiredPostIds()) {
            try {
                searchIndexService.softDeletePost(postId);
            } catch (RuntimeException exception) {
                runtimeFailure = true;
                log.error("Retired post search-index deletion failed for post {}", postId, exception);
            }
        }

        boolean partial = publicationCommitFailure || runtimeFailure || !indexFailures.isEmpty();
        String status = partial ? "partial" : "completed";
        String failedStage = publicationCommitFailure
                ? "media-commit"
                : (runtimeFailure ? "runtime-state" : (!indexFailures.isEmpty() ? "search-index" : null));
        return report(status, failedStage, pack, snapshot, write, attemptedPostIds, pendingViews,
                indexFailures, validation, quality);
    }

    private String newRunId() {
        return Instant.now(clock).toString().replace(':', '-') + "-" + UUID.randomUUID();
    }

    private ContentPackImportReport failed(
            ContentPack pack,
            String stage,
            ValidationResult validation,
            QualityGateResult quality,
            SnapshotRef snapshot) {
        return report("failed", stage, pack, snapshot, null, List.of(), List.of(), List.of(), validation, quality);
    }

    private ContentPackImportReport report(
            String status,
            String failedStage,
            ContentPack pack,
            SnapshotRef snapshot,
            WriteResult write,
            List<Long> attemptedPostIds,
            List<Long> pendingViews,
            List<Long> indexFailures,
            ValidationResult validation,
            QualityGateResult quality) {
        return new ContentPackImportReport(
                status,
                failedStage,
                pack == null || pack.manifest() == null ? null : pack.manifest().namespace(),
                pack == null || pack.manifest() == null ? null : pack.manifest().version(),
                snapshot,
                write == null ? List.of() : write.changedPostIds(),
                write == null ? List.of() : write.retiredPostIds(),
                write == null ? List.of() : write.unmatchedRetirementSlugs(),
                attemptedPostIds,
                pendingViews,
                indexFailures,
                validation == null ? List.of() : validation.warnings(),
                quality == null ? List.of() : quality.warnings(),
                quality == null ? List.of() : quality.errors());
    }
}
