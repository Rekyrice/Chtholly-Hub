package com.chtholly.post.service.impl;

import com.chtholly.llm.rag.PostRagIndexer;
import com.chtholly.search.index.SearchIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Applies best-effort Elasticsearch and RAG side effects without weakening database commits. */
@Component
public class PostSearchCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PostSearchCoordinator.class);

    private final SearchIndexService searchIndexService;
    private final PostRagIndexer ragIndexer;

    /**
     * Creates the post search side-effect coordinator.
     *
     * @param searchIndexService Elasticsearch index writer
     * @param ragIndexer post RAG indexer
     */
    public PostSearchCoordinator(SearchIndexService searchIndexService, PostRagIndexer ragIndexer) {
        this.searchIndexService = searchIndexService;
        this.ragIndexer = ragIndexer;
    }

    void upsert(long postId) {
        try {
            searchIndexService.upsertPost(postId);
        } catch (Exception failure) {
            log.warn("Search index upsert failed, post {} (will retry on backfill): {}",
                    postId, failure.getMessage(), failure);
        }
    }

    void delete(long postId) {
        try {
            searchIndexService.softDeletePost(postId);
        } catch (Exception failure) {
            log.warn("Search index delete failed, post {} (will retry on backfill): {}",
                    postId, failure.getMessage(), failure);
        }
    }

    void preIndexAfterContentConfirm(long postId) {
        try {
            ragIndexer.ensureIndexed(postId);
        } catch (Exception failure) {
            log.warn("Pre-index after content confirm failed, post {}: {}", postId, failure.getMessage());
        }
    }

    void preIndexAfterPublish(long postId) {
        try {
            ragIndexer.ensureIndexed(postId);
        } catch (Exception failure) {
            log.warn("Pre-index after publish failed, post {} (RAG backfill may recover): {}",
                    postId, failure.getMessage(), failure);
        }
    }

    void refreshRagAfterVisibilityChange(long postId) {
        try {
            ragIndexer.ensureIndexed(postId);
        } catch (Exception failure) {
            log.warn("RAG visibility refresh failed, post {} (Outbox replay may recover): {}",
                    postId, failure.getMessage(), failure);
        }
    }
}
