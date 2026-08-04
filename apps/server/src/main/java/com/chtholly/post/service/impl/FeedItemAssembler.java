package com.chtholly.post.service.impl;

import com.chtholly.comment.service.CommentService;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.model.PostFeedRow;
import com.chtholly.user.model.PublicAuthorSnapshot;
import com.chtholly.user.service.PublicAuthorQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Assembles feed rows and cached fragments with counters, user flags, comments, and current author profiles. */
@Component
public class FeedItemAssembler {

    private static final Logger log = LoggerFactory.getLogger(FeedItemAssembler.class);
    private static final List<String> REACTION_TYPES = List.of("like", "fav");

    private final CounterService counterService;
    private final CommentService commentService;
    private final PublicAuthorQueryService publicAuthorQueryService;

    /**
     * Creates the shared feed item assembler.
     *
     * @param counterService reaction count and membership service
     * @param commentService active comment count service
     * @param publicAuthorQueryService current public author profile reader
     */
    public FeedItemAssembler(
            CounterService counterService,
            CommentService commentService,
            PublicAuthorQueryService publicAuthorQueryService) {
        this.counterService = counterService;
        this.commentService = commentService;
        this.publicAuthorQueryService = publicAuthorQueryService;
    }

    List<FeedItemResponse> mapRows(
            List<PostFeedRow> rows,
            Long userId,
            boolean includeTop) {
        List<FeedItemResponse> items = new ArrayList<>(rows.size());
        for (PostFeedRow row : rows) {
            String postId = String.valueOf(row.getId());
            Map<String, Long> counts = counterService.getCounts("post", postId, REACTION_TYPES);
            boolean liked = userId != null && counterService.isLiked("post", postId, userId);
            boolean faved = userId != null && counterService.isFaved("post", postId, userId);
            items.add(FeedItemResponse.fromRow(
                    row,
                    FeedItemResponse.CounterSnapshot.from(counts),
                    liked,
                    faved).withTop(includeTop ? row.getIsTop() : null));
        }
        return items;
    }

    List<FeedItemResponse> fromRows(
            List<PostFeedRow> rows,
            Long userId,
            boolean includeTop) {
        return refreshAuthorsAndComments(mapRows(rows, userId, includeTop));
    }

    List<FeedItemResponse> fromPublicRows(
            List<PostFeedRow> rows,
            Long currentUserId,
            boolean includeTop) {
        return enrich(mapRows(rows, null, includeTop), currentUserId);
    }

    List<FeedItemResponse> fromRowsBatch(List<PostFeedRow> rows, long userId) {
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> stringIds = rows.stream().map(row -> String.valueOf(row.getId())).toList();
        List<Long> postIds = rows.stream().map(PostFeedRow::getId).toList();
        Map<String, Map<String, Long>> counts =
                counterService.getCountsBatch("post", stringIds, REACTION_TYPES);
        Map<Long, Boolean> liked = counterService.batchIsLiked(userId, postIds);
        Map<Long, Boolean> faved = counterService.batchIsFaved(userId, postIds);
        List<FeedItemResponse> items = new ArrayList<>(rows.size());
        for (PostFeedRow row : rows) {
            Map<String, Long> rowCounts = counts.getOrDefault(String.valueOf(row.getId()), Map.of());
            items.add(FeedItemResponse.fromRow(
                    row,
                    FeedItemResponse.CounterSnapshot.from(rowCounts),
                    Boolean.TRUE.equals(liked.get(row.getId())),
                    Boolean.TRUE.equals(faved.get(row.getId()))).withTop(null));
        }
        return refreshAuthorsAndComments(items);
    }

    List<FeedItemResponse> fromCached(List<FeedItemResponse> cached, Long currentUserId) {
        if (cached == null || cached.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> ids = cached.stream().map(FeedItemResponse::id).toList();
        Map<String, Map<String, Long>> counts =
                counterService.getCountsBatch("post", ids, REACTION_TYPES);
        List<FeedItemResponse> withCounts = cached.stream()
                .map(item -> {
                    Map<String, Long> itemCounts = counts.getOrDefault(item.id(), Map.of());
                    return item.withCounts(
                            itemCounts.getOrDefault("like", 0L),
                            itemCounts.getOrDefault("fav", 0L)).withoutUserFlags();
                })
                .toList();
        return enrich(withCounts, currentUserId);
    }

    List<FeedItemResponse> enrich(List<FeedItemResponse> base, Long userId) {
        if (base == null || base.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> postIds = base.stream()
                .map(FeedItemResponse::id)
                .map(Long::parseLong)
                .toList();
        Map<Long, Long> commentCounts = commentService.countActiveByPostIds(postIds);
        Map<Long, Boolean> liked = userId == null ? Map.of() : counterService.batchIsLiked(userId, postIds);
        Map<Long, Boolean> faved = userId == null ? Map.of() : counterService.batchIsFaved(userId, postIds);
        List<FeedItemResponse> enriched = new ArrayList<>(base.size());
        for (FeedItemResponse item : base) {
            long postId = Long.parseLong(item.id());
            enriched.add(item.withUserFlags(
                            userId != null && Boolean.TRUE.equals(liked.get(postId)),
                            userId != null && Boolean.TRUE.equals(faved.get(postId)))
                    .withCommentCount(commentCounts.getOrDefault(postId, 0L)));
        }
        return refreshAuthors(enriched);
    }

    List<FeedItemResponse> refreshAuthorsAndComments(List<FeedItemResponse> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> postIds = items.stream()
                .map(FeedItemResponse::id)
                .map(FeedItemAssembler::parseLongOrNull)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Long, Long> comments = commentService.countActiveByPostIds(postIds);
        return refreshAuthors(items).stream()
                .map(item -> {
                    Long postId = parseLongOrNull(item.id());
                    return item.withCommentCount(postId == null ? 0L : comments.getOrDefault(postId, 0L));
                })
                .toList();
    }

    private List<FeedItemResponse> refreshAuthors(List<FeedItemResponse> items) {
        List<Long> authorIds = items.stream()
                .map(FeedItemResponse::authorId)
                .map(FeedItemAssembler::parseLongOrNull)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
        Map<Long, PublicAuthorSnapshot> authors = Map.of();
        if (!authorIds.isEmpty()) {
            try {
                authors = publicAuthorQueryService.findByIds(authorIds);
            } catch (RuntimeException failure) {
                log.warn("Failed to refresh feed author profiles, authorIds={}", authorIds, failure);
            }
        }
        Map<Long, PublicAuthorSnapshot> resolved = authors;
        return items.stream().map(item -> withAuthor(item, resolved)).toList();
    }

    private FeedItemResponse withAuthor(
            FeedItemResponse item,
            Map<Long, PublicAuthorSnapshot> authors) {
        Long authorId = parseLongOrNull(item.authorId());
        PublicAuthorSnapshot author = authorId == null ? null : authors.get(authorId);
        if (author != null) {
            return item.withAuthor(
                    String.valueOf(author.id()),
                    author.handle(),
                    author.avatar(),
                    author.nickname(),
                    author.tagsJson());
        }
        if (item.authorNickname() == null || item.authorNickname().isBlank()) {
            return item.withAuthor(
                    item.authorId(),
                    item.authorHandle(),
                    item.authorAvatar(),
                    "已注销用户",
                    item.tagJson());
        }
        return item;
    }

    static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException failure) {
            log.debug("Feed identifier is not numeric: {}", value, failure);
            return null;
        }
    }
}
