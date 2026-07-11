package com.chtholly.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch.core.MsearchResponse;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchItem;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem;
import co.elastic.clients.elasticsearch.core.msearch.RequestItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.chtholly.agent.api.dto.AgentExperienceResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.search.api.dto.HubFeedResponse;
import com.chtholly.search.api.dto.TagCountResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Executes and parses the Hub page's multi-region Elasticsearch request. */
@Service
@Slf4j
public class HubFeedSearchService {
    private static final String INDEX = "chtholly_content_index";
    private static final String EXPERIENCE_INDEX = "experiences";
    private static final String STATUS_OK = "ok";
    private static final String STATUS_DEGRADED = "degraded";
    private static final String HOT_TAGS_AGG = "hot_tags";
    private static final int DEFAULT_HUB_PAGE = 1;
    private static final int MAX_HUB_PAGE_SIZE = 50;

    private final ElasticsearchClient es;
    private final SearchHitMapper hitMapper;

    public HubFeedSearchService(ElasticsearchClient es, SearchHitMapper hitMapper) {
        this.es = es;
        this.hitMapper = hitMapper;
    }

    private enum HubRegion {
        LATEST_POSTS, HOT_TAGS, RECOMMENDATIONS, EXPERIENCES
    }

    @SuppressWarnings("unchecked")
    public HubFeedResponse hubFeed(String interestTags, Long currentUserIdNullable, int page, int size) {
        List<String> tags = parseCsv(interestTags);
        int safePage = Math.max(page, DEFAULT_HUB_PAGE);
        int safeSize = Math.min(Math.max(size, 1), MAX_HUB_PAGE_SIZE);
        MsearchResponse<Map<String, Object>> response;
        try {
            response = es.msearch(ms -> ms
                            .searches(latestPostsRequest(safePage, safeSize))
                            .searches(hotTagsRequest())
                            .searches(recommendationsRequest(tags))
                            .searches(experiencesRequest()),
                    (Class<Map<String, Object>>) (Class<?>) Map.class);
        } catch (Exception e) {
            log.warn("Hub msearch failed: {}", e.getMessage(), e);
            return degradedHubFeed();
        }

        List<MultiSearchResponseItem<Map<String, Object>>> responses =
                response.responses() == null ? Collections.emptyList() : response.responses();

        RegionResult<FeedItemResponse> latest = parseFeedRegion(responses, HubRegion.LATEST_POSTS, currentUserIdNullable);
        RegionResult<TagCountResponse> hotTags = parseHotTagsRegion(responses);
        RegionResult<FeedItemResponse> recommendations =
                parseFeedRegion(responses, HubRegion.RECOMMENDATIONS, currentUserIdNullable);
        RegionResult<AgentExperienceResponse> experiences = parseExperiencesRegion(responses);

        return new HubFeedResponse(
                latest.items(), latest.status(),
                latest.total(),
                hotTags.items(), hotTags.status(),
                recommendations.items(), recommendations.status(),
                experiences.items(), experiences.status()
        );
    }


    private RequestItem latestPostsRequest(int page, int size) {
        int from = (page - 1) * size;
        return RequestItem.of(r -> r
                .header(h -> h.index(INDEX))
                .body(b -> b
                        .from(from)
                        .size(size)
                        .query(q -> q.term(t -> t.field("status").value("published")))
                        .sort(s -> s.field(f -> f.field("publish_time").order(SortOrder.Desc)))));
    }

    private RequestItem hotTagsRequest() {
        return RequestItem.of(r -> r
                .header(h -> h.index(INDEX))
                .body(b -> b
                        .size(0)
                        .query(q -> q.term(t -> t.field("status").value("published")))
                        .aggregations(HOT_TAGS_AGG, a -> a.terms(t -> t.field("tags").size(20)))));
    }

    private RequestItem recommendationsRequest(List<String> tags) {
        return RequestItem.of(r -> r
                .header(h -> h.index(INDEX))
                .body(b -> b
                        .size(5)
                        .query(q -> q.bool(bool -> {
                            bool.filter(f -> f.term(t -> t.field("status").value("published")));
                            if (tags != null && !tags.isEmpty()) {
                                bool.must(m -> m.terms(t -> t.field("tags")
                                        .terms(tv -> tv.value(tags.stream().map(FieldValue::of).toList()))));
                            } else {
                                bool.must(m -> m.matchAll(ma -> ma));
                            }
                            return bool;
                        }))
                        .sort(s -> s.field(f -> f.field("like_count").order(SortOrder.Desc)))
                        .sort(s -> s.field(f -> f.field("publish_time").order(SortOrder.Desc)))));
    }

    private RequestItem experiencesRequest() {
        return RequestItem.of(r -> r
                .header(h -> h.index(EXPERIENCE_INDEX).ignoreUnavailable(true))
                .body(b -> b
                        .size(8)
                        .sort(s -> s.field(f -> f.field("createdAt").order(SortOrder.Desc)))));
    }

    private HubFeedResponse degradedHubFeed() {
        return new HubFeedResponse(
                Collections.emptyList(), STATUS_DEGRADED, 0,
                Collections.emptyList(), STATUS_DEGRADED,
                Collections.emptyList(), STATUS_DEGRADED,
                Collections.emptyList(), STATUS_DEGRADED
        );
    }

    private RegionResult<FeedItemResponse> parseFeedRegion(
            List<MultiSearchResponseItem<Map<String, Object>>> responses,
            HubRegion region,
            Long currentUserIdNullable) {
        MultiSearchItem<Map<String, Object>> item = resultOrNull(responses, region);
        if (item == null) {
            return RegionResult.degraded();
        }
        return new RegionResult<>(hitMapper.mapPostHits(hitsOf(item), currentUserIdNullable), STATUS_OK, totalHitsOf(item));
    }

    private RegionResult<TagCountResponse> parseHotTagsRegion(
            List<MultiSearchResponseItem<Map<String, Object>>> responses) {
        MultiSearchItem<Map<String, Object>> item = resultOrNull(responses, HubRegion.HOT_TAGS);
        if (item == null) {
            return RegionResult.degraded();
        }
        Aggregate aggregate = item.aggregations() == null ? null : item.aggregations().get(HOT_TAGS_AGG);
        if (aggregate == null || !aggregate.isSterms()) {
            return new RegionResult<>(Collections.emptyList(), STATUS_OK, 0);
        }
        List<TagCountResponse> tags = aggregate.sterms().buckets().array().stream()
                .map(bucket -> {
                    String name = fieldValueToString(bucket.key());
                    return new TagCountResponse(name, name, name, bucket.docCount());
                })
                .toList();
        return new RegionResult<>(tags, STATUS_OK, 0);
    }

    private RegionResult<AgentExperienceResponse> parseExperiencesRegion(
            List<MultiSearchResponseItem<Map<String, Object>>> responses) {
        MultiSearchItem<Map<String, Object>> item = resultOrNull(responses, HubRegion.EXPERIENCES);
        if (item == null) {
            return RegionResult.degraded();
        }
        List<AgentExperienceResponse> experiences = hitsOf(item).stream()
                .map(Hit::source)
                .filter(source -> source != null)
                .map(source -> new AgentExperienceResponse(
                        asString(source.get("text")),
                        asDouble(source.get("valueScore"), 0.0),
                        asInt(source.get("importance"), 1),
                        asInstant(source.get("createdAt")),
                        asString(source.get("source"))))
                .toList();
        return new RegionResult<>(experiences, STATUS_OK, 0);
    }

    private MultiSearchItem<Map<String, Object>> resultOrNull(
            List<MultiSearchResponseItem<Map<String, Object>>> responses,
            HubRegion region) {
        int index = region.ordinal();
        if (index >= responses.size()) {
            log.warn("Hub msearch missing response for region={}", region);
            return null;
        }
        MultiSearchResponseItem<Map<String, Object>> item = responses.get(index);
        if (item == null || item.isFailure()) {
            log.warn("Hub msearch subquery degraded region={}", region);
            return null;
        }
        try {
            return item.result();
        } catch (Exception e) {
            log.warn("Hub msearch response parse failed region={}: {}", region, e.getMessage(), e);
            return null;
        }
    }

    private List<Hit<Map<String, Object>>> hitsOf(MultiSearchItem<Map<String, Object>> item) {
        return item.hits() == null ? Collections.emptyList() : item.hits().hits();
    }

    private int totalHitsOf(MultiSearchItem<Map<String, Object>> item) {
        if (item.hits() == null || item.hits().total() == null) {
            return hitsOf(item).size();
        }
        long total = item.hits().total().value();
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }


    private String fieldValueToString(FieldValue fv) {
        if (fv.isDouble()) {
            return String.valueOf(fv.doubleValue());
        }
        if (fv.isLong()) {
            return String.valueOf(fv.longValue());
        }
        if (fv.isString()) {
            return fv.stringValue();
        }
        if (fv.isBoolean()) {
            return String.valueOf(fv.booleanValue());
        }

        return String.valueOf(fv._get());
    }

    /**
     * 任意对象转字符串，null 保护。
     */

    private String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 任意对象转 Long（Number 直接转换，字符串容错解析）。
     */
    private Long asLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }

        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    private Double asDouble(Object o, double fallback) {
        if (o == null) {
            return fallback;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return fallback;
        }
    }

    private int asInt(Object o, int fallback) {
        if (o == null) {
            return fallback;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return fallback;
        }
    }

    private Instant asInstant(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Instant instant) {
            return instant;
        }
        if (o instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        try {
            return Instant.parse(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }


    private List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private record RegionResult<T>(List<T> items, String status, int total) {
        private static <T> RegionResult<T> degraded() {
            return new RegionResult<>(Collections.emptyList(), STATUS_DEGRADED, 0);
        }
    }
}

