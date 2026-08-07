package com.chtholly.seed.contentpack;

import com.chtholly.llm.rag.PostRagIndexer;
import com.chtholly.search.index.SearchIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Synchronizes search and RAG projections after a content-pack database commit. */
@Component
public final class ContentPackPostProjectionSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(ContentPackPostProjectionSynchronizer.class);

    private final SearchIndexService searchIndexService;
    private final PostRagIndexer ragIndexer;

    public ContentPackPostProjectionSynchronizer(
            SearchIndexService searchIndexService,
            PostRagIndexer ragIndexer) {
        this.searchIndexService = Objects.requireNonNull(searchIndexService, "searchIndexService");
        this.ragIndexer = Objects.requireNonNull(ragIndexer, "ragIndexer");
    }

    /** Best-effort synchronization that preserves every failed or skipped post identifier. */
    public SyncResult synchronize(List<Long> activePostIds, List<Long> retiredPostIds) {
        List<Long> safeActivePostIds = activePostIds == null ? List.of() : activePostIds;
        List<Long> safeRetiredPostIds = retiredPostIds == null ? List.of() : retiredPostIds;
        List<Long> searchFailures = synchronizeSearch(safeActivePostIds);
        boolean retiredSearchFailure = synchronizeRetiredSearch(safeRetiredPostIds);

        LinkedHashSet<Long> ragPostIds = new LinkedHashSet<>(safeActivePostIds);
        ragPostIds.addAll(safeRetiredPostIds);
        List<Long> ragFailures = new ArrayList<>();
        List<Long> ragSkipped = new ArrayList<>();
        boolean ragEnabled;
        try {
            ragEnabled = ragIndexer.isEnabled();
        } catch (RuntimeException exception) {
            ragEnabled = false;
            ragFailures.addAll(ragPostIds);
            log.error("Unable to determine whether post RAG indexing is enabled", exception);
        }
        if (!ragEnabled && ragFailures.isEmpty()) {
            ragSkipped.addAll(ragPostIds);
        } else if (ragEnabled) {
            for (long postId : ragPostIds) {
                try {
                    ragIndexer.ensureIndexed(postId);
                } catch (RuntimeException exception) {
                    ragFailures.add(postId);
                    log.error("Seed post RAG indexing failed for post {}", postId, exception);
                }
            }
        }
        return new SyncResult(searchFailures, ragFailures, ragSkipped, retiredSearchFailure);
    }

    private List<Long> synchronizeSearch(List<Long> activePostIds) {
        List<Long> failures = new ArrayList<>();
        for (long postId : activePostIds) {
            try {
                if (!searchIndexService.tryUpsertPost(postId)) {
                    failures.add(postId);
                }
            } catch (RuntimeException exception) {
                failures.add(postId);
                log.error("Seed post indexing invocation failed for post {}", postId, exception);
            }
        }
        return failures;
    }

    private boolean synchronizeRetiredSearch(List<Long> retiredPostIds) {
        boolean failed = false;
        for (long postId : retiredPostIds) {
            try {
                searchIndexService.softDeletePost(postId);
            } catch (RuntimeException exception) {
                failed = true;
                log.error("Retired post search-index deletion failed for post {}", postId, exception);
            }
        }
        return failed;
    }

    public record SyncResult(
            List<Long> searchFailures,
            List<Long> ragFailures,
            List<Long> ragSkipped,
            boolean retiredSearchFailure) {

        public SyncResult {
            searchFailures = List.copyOf(searchFailures == null ? List.of() : searchFailures);
            ragFailures = List.copyOf(ragFailures == null ? List.of() : ragFailures);
            ragSkipped = List.copyOf(ragSkipped == null ? List.of() : ragSkipped);
        }
    }
}
