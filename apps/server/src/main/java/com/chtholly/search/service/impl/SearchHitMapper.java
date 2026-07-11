package com.chtholly.search.service.impl;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.api.dto.FeedItemResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Maps Elasticsearch post documents into feed responses with user-specific interaction state.
 *
 * <p>The mapper is shared by full-text search, recommendations, and Hub aggregation so index
 * schema changes have one translation boundary.
 */
@Component
public class SearchHitMapper {

    private final CounterService counterService;

    public SearchHitMapper(CounterService counterService) {
        this.counterService = counterService;
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

        List<FeedItemResponse> items = new ArrayList<>(hits.size());
        for (Hit<Map<String, Object>> hit : hits) {
            Map<String, Object> source = hit.source();
            if (source == null) continue;
            Long postId = asLong(source.get("content_id"));
            String snippet = highlightedSnippet(hit);
            FeedItemResponse item = FeedItemResponse.fromEsHit(
                    source,
                    postId != null && Boolean.TRUE.equals(liked.get(postId)),
                    postId != null && Boolean.TRUE.equals(faved.get(postId)));
            if (snippet != null && !snippet.isBlank()) item = item.withDescription(snippet);
            items.add(item.withTop(null));
        }
        return List.copyOf(items);
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
}
