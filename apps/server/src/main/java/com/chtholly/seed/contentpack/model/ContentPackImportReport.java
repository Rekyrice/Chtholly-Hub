package com.chtholly.seed.contentpack.model;

import com.chtholly.seed.contentpack.ContentPackSnapshotWriter.SnapshotRef;

import java.util.List;

/**
 * Redacted outcome of one content-pack validation or formal import run.
 *
 * @param status validated, completed, partial or failed
 * @param failedStage first stage that did not complete, or {@code null}
 * @param namespace public content-pack namespace, when loading reached the manifest
 * @param version public content-pack version, when loading reached the manifest
 * @param snapshot pre-import snapshot location, only for formal runs that reached that stage
 * @param changedPostIds posts whose relational content changed
 * @param attemptedPostIds all pack posts reconciled after the database boundary
 * @param pendingViewPostIds view baselines not yet visible before the bounded wait expired
 * @param indexFailures attempted pack posts that could not be indexed from full content
 * @param validationWarnings non-blocking structural diagnostics
 * @param qualityWarnings non-blocking prose diagnostics
 * @param qualityErrors blocking prose diagnostics
 */
public record ContentPackImportReport(
        String status,
        String failedStage,
        String namespace,
        String version,
        SnapshotRef snapshot,
        List<Long> changedPostIds,
        List<Long> attemptedPostIds,
        List<Long> pendingViewPostIds,
        List<Long> indexFailures,
        List<String> validationWarnings,
        List<String> qualityWarnings,
        List<String> qualityErrors) {

    /** Protects report collections from mutation by CLI serializers or callers. */
    public ContentPackImportReport {
        changedPostIds = immutable(changedPostIds);
        attemptedPostIds = immutable(attemptedPostIds);
        pendingViewPostIds = immutable(pendingViewPostIds);
        indexFailures = immutable(indexFailures);
        validationWarnings = immutable(validationWarnings);
        qualityWarnings = immutable(qualityWarnings);
        qualityErrors = immutable(qualityErrors);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
