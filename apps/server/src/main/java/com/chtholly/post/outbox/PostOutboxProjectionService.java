package com.chtholly.post.outbox;

import com.chtholly.counter.service.UserCounterService;
import com.chtholly.llm.rag.PostRagIndexer;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.post.feed.FeedTimelineService;
import com.chtholly.post.service.PostFeedService;
import com.chtholly.post.service.impl.PostCacheInvalidator;
import com.chtholly.search.index.SearchIndexService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Rebuilds idempotent post projections from durable Outbox facts. */
@Service
public class PostOutboxProjectionService {

    private static final Set<String> STRUCTURAL_EVENTS = Set.of(
            "PostPublished",
            "PostMetadataUpdated",
            "PostTopChanged",
            "PostVisibilityChanged",
            "PostDeleted");

    private final PostMapper postMapper;
    private final PostCacheInvalidator cacheInvalidator;
    private final PostFeedService postFeedService;
    private final UserCounterService userCounterService;
    private final PostRagIndexer ragIndexer;
    private final SearchIndexService searchIndexService;
    private final FeedTimelineService feedTimelineService;

    /**
     * Creates the replayable post projection boundary.
     *
     * @param postMapper authoritative post reader
     * @param cacheInvalidator detail and public-feed cache invalidator
     * @param postFeedService author-feed cache boundary
     * @param userCounterService rebuildable author counter projection
     * @param ragIndexer post RAG projection writer
     * @param searchIndexService public search projection writer
     * @param feedTimelineService follower timeline projection writer
     */
    public PostOutboxProjectionService(
            PostMapper postMapper,
            PostCacheInvalidator cacheInvalidator,
            PostFeedService postFeedService,
            UserCounterService userCounterService,
            PostRagIndexer ragIndexer,
            SearchIndexService searchIndexService,
            FeedTimelineService feedTimelineService) {
        this.postMapper = postMapper;
        this.cacheInvalidator = cacheInvalidator;
        this.postFeedService = postFeedService;
        this.userCounterService = userCounterService;
        this.ragIndexer = ragIndexer;
        this.searchIndexService = searchIndexService;
        this.feedTimelineService = feedTimelineService;
    }

    /** Applies one idempotent projection update; unknown post event types are ignored. */
    public void project(String eventType, long postId) {
        if (!supports(eventType)) {
            return;
        }

        Post post = postMapper.findById(postId);
        List<ProjectionFailure> failures = new ArrayList<>();
        attempt(failures, "detail-cache", () -> cacheInvalidator.invalidateStrict(postId));
        if (STRUCTURAL_EVENTS.contains(eventType)) {
            attempt(
                    failures,
                    "public-feed-cache",
                    cacheInvalidator::invalidateAllPublicFeedPagesStrict);
        }
        if (post != null && post.getCreatorId() != null) {
            long creatorId = post.getCreatorId();
            attempt(
                    failures,
                    "author-feed-cache",
                    () -> postFeedService.invalidateMyPublishedCacheStrict(creatorId));
            attempt(
                    failures,
                    "following-author-cache",
                    () -> postFeedService.invalidateFollowingAuthorCacheStrict(creatorId));
            if ("PostPublished".equals(eventType) || "PostDeleted".equals(eventType)) {
                attempt(
                        failures,
                        "author-counter-cache",
                        () -> userCounterService.invalidateReactionCounters(creatorId));
            }
        }

        attempt(failures, "search-index", () -> searchIndexService.upsertPost(postId));
        if (!"PostTopChanged".equals(eventType)) {
            attempt(failures, "rag-index", () -> ragIndexer.ensureIndexed(postId));
        }
        if ("PostPublished".equals(eventType)
                || "PostMetadataUpdated".equals(eventType)
                || "PostVisibilityChanged".equals(eventType)
                || "PostDeleted".equals(eventType)) {
            attempt(
                    failures,
                    "follower-timeline",
                    () -> feedTimelineService.reconcilePost(postId, post));
        }
        throwIfAnyProjectionFailed(postId, eventType, failures);
    }

    static boolean supports(String eventType) {
        return "PostContentConfirmed".equals(eventType)
                || "PostMetadataUpdated".equals(eventType)
                || "PostPublished".equals(eventType)
                || "PostTopChanged".equals(eventType)
                || "PostVisibilityChanged".equals(eventType)
                || "PostDeleted".equals(eventType);
    }

    private static void attempt(
            List<ProjectionFailure> failures,
            String sink,
            Runnable projection) {
        try {
            projection.run();
        } catch (RuntimeException failure) {
            failures.add(new ProjectionFailure(sink, failure));
        }
    }

    private static void throwIfAnyProjectionFailed(
            long postId,
            String eventType,
            List<ProjectionFailure> failures) {
        if (failures.isEmpty()) {
            return;
        }
        String failedSinks = failures.stream()
                .map(ProjectionFailure::sink)
                .collect(Collectors.joining(","));
        IllegalStateException aggregate = new IllegalStateException(
                "Post projection failed for post %d event %s sinks=%s"
                        .formatted(postId, eventType, failedSinks));
        failures.forEach(failure -> aggregate.addSuppressed(
                new IllegalStateException(
                        "Post projection sink failed: " + failure.sink(),
                        failure.cause())));
        throw aggregate;
    }

    private record ProjectionFailure(String sink, RuntimeException cause) {}

}
