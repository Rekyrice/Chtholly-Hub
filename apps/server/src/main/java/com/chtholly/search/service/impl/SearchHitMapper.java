package com.chtholly.search.service.impl;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.chtholly.counter.service.CounterService;
import com.chtholly.comment.service.CommentService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.user.model.PublicAuthorSnapshot;
import com.chtholly.user.service.PublicAuthorQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Maps Elasticsearch post documents into feed responses with user-specific interaction state.
 *
 * <p>The mapper is shared by full-text search, recommendations, and Hub aggregation so index
 * schema changes have one translation boundary.
 */
@Component
@Slf4j
public class SearchHitMapper {

    private final CounterService counterService;
    private final PublicAuthorQueryService publicAuthorQueryService;
    private final CommentService commentService;

    public SearchHitMapper(
            CounterService counterService,
            PublicAuthorQueryService publicAuthorQueryService,
            CommentService commentService) {
        this.counterService = counterService;
        this.publicAuthorQueryService = publicAuthorQueryService;
        this.commentService = commentService;
    }

    /** Maps post hits and enriches liked/faved state in two batch calls. */
    public List<FeedItemResponse> mapPostHits(
            List<Hit<Map<String, Object>>> hits,
            Long currentUserId
    ) {
        if (hits == null || hits.isEmpty()) return Collections.emptyList();

        List<Long> postIds = hits.stream()
                .map(Hit::source)
                .filter(source -> source != null)
                .map(source -> asLong(source.get("content_id")))
                .filter(id -> id != null)
                .toList();
        Map<Long, Boolean> liked = currentUserId == null
                ? Collections.emptyMap()
                : counterService.batchIsLiked(currentUserId, postIds);
        Map<Long, Boolean> faved = currentUserId == null
                ? Collections.emptyMap()
                : counterService.batchIsFaved(currentUserId, postIds);
        Map<String, Map<String, Long>> reactionCounts = loadReactionCounts(postIds);
        Map<Long, Long> commentCounts = commentService.countActiveByPostIds(postIds);

        List<FeedItemResponse> items = new ArrayList<>(hits.size());
        for (Hit<Map<String, Object>> hit : hits) {
            Map<String, Object> source = hit.source();
            if (source == null) continue;
            Long postId = asLong(source.get("content_id"));
            String snippet = highlightedSnippet(hit);
            Map<String, Long> counts = postId == null
                    ? Map.of()
                    : reactionCounts.getOrDefault(String.valueOf(postId), Map.of());
            FeedItemResponse item = FeedItemResponse.fromEsHit(
                    source,
                    postId != null && Boolean.TRUE.equals(liked.get(postId)),
                    postId != null && Boolean.TRUE.equals(faved.get(postId)))
                    .withCounts(
                            counts.getOrDefault("like", asLongOrZero(source.get("like_count"))),
                            counts.getOrDefault("fav", asLongOrZero(source.get("favorite_count"))))
                    .withCommentCount(postId == null ? 0L : commentCounts.getOrDefault(postId, 0L));
            if (snippet != null && !snippet.isBlank()) item = item.withDescription(snippet);
            items.add(item.withTop(null));
        }
        List<Long> authorIds = items.stream()
                .map(FeedItemResponse::authorId)
                .map(this::asLong)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
        Map<Long, PublicAuthorSnapshot> authors = Map.of();
        if (!authorIds.isEmpty()) {
            try {
                authors = publicAuthorQueryService.findByIds(authorIds);
            } catch (RuntimeException e) {
                log.warn("Failed to refresh search author profiles, authorIds={}", authorIds, e);
            }
        }

        List<FeedItemResponse> refreshed = new ArrayList<>(items.size());
        for (FeedItemResponse item : items) {
            Long authorId = asLong(item.authorId());
            PublicAuthorSnapshot author = authorId == null ? null : authors.get(authorId);
            if (author != null) {
                refreshed.add(item.withAuthor(
                        String.valueOf(author.id()), author.handle(), author.avatar(), author.nickname(), author.tagsJson()));
            } else if (item.authorNickname() == null || item.authorNickname().isBlank()) {
                refreshed.add(item.withAuthor(item.authorId(), item.authorHandle(), item.authorAvatar(),
                        "已注销用户", item.tagJson()));
            } else {
                refreshed.add(item);
            }
        }
        return List.copyOf(refreshed);
    }

    private Map<String, Map<String, Long>> loadReactionCounts(List<Long> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        try {
            return counterService.getCountsBatch(
                    "post",
                    postIds.stream().map(String::valueOf).toList(),
                    List.of("like", "fav"));
        } catch (RuntimeException exception) {
            log.warn("Failed to refresh search reaction counts, postIds={}", postIds, exception);
            return Map.of();
        }
    }

    private String highlightedSnippet(Hit<Map<String, Object>> hit) {
        Map<String, List<String>> highlights = hit.highlight();
        if (highlights == null || highlights.isEmpty()) return null;
        for (String field : List.of("title", "description", "content")) {
            List<String> fragments = highlights.get(field);
            if (fragments != null && !fragments.isEmpty()) {
                return cleanSnippet(fragments.getFirst());
            }
        }
        return null;
    }

    private String cleanSnippet(String raw) {
        if (raw == null) return null;
        String text = raw.replace("<em>", "").replace("</em>", "").trim();
        return text.length() > 240 ? text.substring(0, 240) + "..." : text;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private long asLongOrZero(Object value) {
        Long parsed = asLong(value);
        return parsed == null ? 0L : Math.max(0L, parsed);
    }
}
