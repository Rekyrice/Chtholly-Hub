package com.chtholly.post.service.impl;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.feed.FeedTimelineService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostFeedRow;
import com.chtholly.post.util.FeedCursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Composes the authenticated following feed from bounded Redis candidates and MySQL facts.
 *
 * <p>Redis timeline and author caches only discover candidates. Every candidate is authorized by
 * MySQL, and incomplete projections fall back to stable active-relation pagination.
 */
@Service
public class FollowingPostFeedQueryService {

    private static final Logger log = LoggerFactory.getLogger(FollowingPostFeedQueryService.class);
    private static final int TIMELINE_SCAN_BATCH_SIZE = 50;
    private static final int MAX_TIMELINE_SCAN_CANDIDATES = 1_000;

    private final PostMapper mapper;
    private final FeedTimelineService feedTimelineService;
    private final FollowingAuthorPostCache authorPostCache;
    private final FeedItemAssembler assembler;

    /**
     * Creates the following-feed query service.
     *
     * @param mapper authoritative post feed reader
     * @param feedTimelineService bounded Redis timeline reader
     * @param authorPostCache large-author candidate cache
     * @param assembler shared response assembler
     */
    public FollowingPostFeedQueryService(
            PostMapper mapper,
            FeedTimelineService feedTimelineService,
            FollowingAuthorPostCache authorPostCache,
            FeedItemAssembler assembler) {
        this.mapper = mapper;
        this.feedTimelineService = feedTimelineService;
        this.authorPostCache = authorPostCache;
        this.assembler = assembler;
    }

    /** Returns one viewer-authorized following-feed page. */
    public PageResponse<FeedItemResponse> getFollowingFeed(long userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 1);
        int offset = (safePage - 1) * safeSize;
        int targetCount = offset + safeSize + 1;
        if (targetCount > MAX_TIMELINE_SCAN_CANDIDATES) {
            return authoritativePage(userId, safePage, safeSize, offset, "deep-page");
        }

        try {
            return projectedPage(userId, safePage, safeSize, offset, targetCount);
        } catch (FollowingFeedProjectionUnavailableException failure) {
            log.warn("feed.following projection unavailable user={} projection={}; falling back to mysql",
                    userId, failure.projection(), failure.getCause());
            return authoritativePage(
                    userId, safePage, safeSize, offset,
                    "projection-unavailable:" + failure.projection());
        }
    }

    private PageResponse<FeedItemResponse> projectedPage(
            long userId,
            int safePage,
            int safeSize,
            int offset,
            int targetCount) {
        List<PostFeedRow> bigVRows = authorPostCache.recentPosts(
                readFollowedBigVAuthors(userId));
        Map<Long, PostFeedRow> authorizedById = new LinkedHashMap<>();
        addAuthorizedRows(authorizedById, authorizeCandidates(
                bigVRows.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(PostFeedRow::getId)
                        .toList(),
                userId));

        Set<Long> authorizedTimelineIds = new HashSet<>();
        Set<Long> rejectedTimelineIds = new LinkedHashSet<>();
        long nextRank = 0L;
        int scannedCandidates = 0;
        boolean timelineExhausted = false;
        while (authorizedTimelineIds.size() < targetCount && !timelineExhausted) {
            int remainingBudget = MAX_TIMELINE_SCAN_CANDIDATES - scannedCandidates;
            if (remainingBudget <= 0) {
                removeRejectedTimelineCandidates(userId, rejectedTimelineIds);
                return authoritativePage(userId, safePage, safeSize, offset, "scan-cap");
            }
            int batchSize = Math.min(TIMELINE_SCAN_BATCH_SIZE, remainingBudget);
            FeedTimelineService.TimelineCandidateBatch batch =
                    readTimelineBatch(userId, nextRank, batchSize);
            if (batch == null || batch.scannedCount() <= 0) {
                timelineExhausted = true;
                break;
            }
            nextRank += batch.scannedCount();
            scannedCandidates += batch.scannedCount();
            List<PostFeedRow> batchRows = authorizeCandidates(batch.postIds(), userId);
            addAuthorizedRows(authorizedById, batchRows);
            batchRows.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(PostFeedRow::getId)
                    .filter(java.util.Objects::nonNull)
                    .forEach(authorizedTimelineIds::add);
            collectRejectedTimelineCandidates(rejectedTimelineIds, batch.postIds(), batchRows);
            timelineExhausted = batch.exhausted();
        }
        if (!timelineExhausted
                && authorizedTimelineIds.size() < targetCount
                && scannedCandidates >= MAX_TIMELINE_SCAN_CANDIDATES) {
            removeRejectedTimelineCandidates(userId, rejectedTimelineIds);
            return authoritativePage(userId, safePage, safeSize, offset, "scan-cap");
        }
        removeRejectedTimelineCandidates(userId, rejectedTimelineIds);

        List<PostFeedRow> sorted = new ArrayList<>(authorizedById.values());
        sorted.sort(Comparator.comparing(
                PostFeedRow::getPublishTime,
                Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(
                        PostFeedRow::getId,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        if (sorted.size() < targetCount) {
            return authoritativePage(userId, safePage, safeSize, offset, "incomplete-projection-page");
        }

        List<PostFeedRow> slice = sorted.stream()
                .skip(offset)
                .limit(safeSize + 1L)
                .toList();
        boolean hasMore = slice.size() > safeSize;
        List<PostFeedRow> pageRows = hasMore ? slice.subList(0, safeSize) : slice;
        List<FeedItemResponse> items = assembler.fromRowsBatch(pageRows, userId);
        log.info("feed.following user={} page={} size={} scanned={} bigvCandidates={} authorized={} hasMore={}",
                userId, safePage, safeSize, scannedCandidates, bigVRows.size(), sorted.size(), hasMore);
        return PageResponse.offset(items, safePage, safeSize, 0L, hasMore,
                hasMore ? nextCursorFromRows(pageRows) : null);
    }

    /** Invalidates one large-author cache while containing Redis failures. */
    public void invalidateAuthorCache(long authorId) {
        authorPostCache.invalidate(authorId);
    }

    /** Invalidates one large-author cache and propagates Redis failures. */
    public void invalidateAuthorCacheStrict(long authorId) {
        authorPostCache.invalidateStrict(authorId);
    }

    private List<Long> readFollowedBigVAuthors(long userId) {
        try {
            return feedTimelineService.getFollowedBigVAuthors(userId);
        } catch (RuntimeException failure) {
            throw new FollowingFeedProjectionUnavailableException(
                    "bigv-author-set", failure);
        }
    }

    private FeedTimelineService.TimelineCandidateBatch readTimelineBatch(
            long userId,
            long startRank,
            int limit) {
        try {
            return feedTimelineService.getTimelinePostIdBatch(userId, startRank, limit);
        } catch (RuntimeException failure) {
            throw new FollowingFeedProjectionUnavailableException(
                    "timeline", failure);
        }
    }

    private List<PostFeedRow> authorizeCandidates(List<Long> candidateIds, long userId) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinctIds = candidateIds.stream()
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
        if (distinctIds.isEmpty()) {
            return List.of();
        }
        List<PostFeedRow> rows = mapper.listFollowingFeedRowsByIds(distinctIds, userId);
        if (rows == null) {
            throw new IllegalStateException("Authoritative following-feed hydration returned null");
        }
        return rows;
    }

    private void addAuthorizedRows(Map<Long, PostFeedRow> target, List<PostFeedRow> rows) {
        for (PostFeedRow row : rows) {
            if (row != null && row.getId() != null) {
                target.putIfAbsent(row.getId(), row);
            }
        }
    }

    private void collectRejectedTimelineCandidates(
            Set<Long> target,
            List<Long> candidates,
            List<PostFeedRow> authorizedRows) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        Set<Long> authorizedIds = authorizedRows.stream()
                .filter(java.util.Objects::nonNull)
                .map(PostFeedRow::getId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        candidates.stream()
                .filter(java.util.Objects::nonNull)
                .filter(id -> !authorizedIds.contains(id))
                .forEach(target::add);
    }

    private void removeRejectedTimelineCandidates(long userId, Set<Long> rejected) {
        if (rejected == null || rejected.isEmpty()) {
            return;
        }
        try {
            feedTimelineService.removeTimelinePostIds(userId, List.copyOf(rejected));
        } catch (RuntimeException failure) {
            log.warn("feed.following stale candidate cleanup failed user={} count={}",
                    userId, rejected.size(), failure);
        }
    }

    private PageResponse<FeedItemResponse> authoritativePage(
            long userId,
            int page,
            int size,
            int offset,
            String reason) {
        List<PostFeedRow> rows = mapper.listFollowingFeedAuthoritative(userId, size + 1, offset);
        if (rows == null) {
            throw new IllegalStateException("Authoritative following-feed page returned null");
        }
        boolean hasMore = rows.size() > size;
        List<PostFeedRow> pageRows = hasMore ? rows.subList(0, size) : rows;
        List<FeedItemResponse> items = assembler.fromRowsBatch(pageRows, userId);
        log.info("feed.following source=mysql user={} page={} size={} reason={} hasMore={}",
                userId, page, size, reason, hasMore);
        return PageResponse.offset(items, page, size, 0L, hasMore,
                hasMore ? nextCursorFromRows(pageRows) : null);
    }

    private String nextCursorFromRows(List<PostFeedRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        PostFeedRow last = rows.getLast();
        if (last.getPublishTime() == null || last.getId() == null) {
            return null;
        }
        return FeedCursor.encode(last.getPublishTime(), last.getId());
    }
}
