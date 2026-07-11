package com.chtholly.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch.core.GetResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns interest, popularity, and related-post recommendation queries. */
@Service
@Slf4j
public class PostRecommendationSearchService {
    private static final String INDEX = "chtholly_content_index";
    private static final int MAX_LIMIT = 50;

    private final ElasticsearchClient es;
    private final SearchHitMapper hitMapper;

    public PostRecommendationSearchService(ElasticsearchClient es, SearchHitMapper hitMapper) {
        this.es = es;
        this.hitMapper = hitMapper;
    }

    @SuppressWarnings("unchecked")
    public List<FeedItemResponse> recommendByInterest(Map<String, Double> tagWeights,
                                                      Collection<Long> excludePostIds,
                                                      int limit,
                                                      Long currentUserIdNullable) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        Set<Long> exclude = normalizeExcludeIds(excludePostIds);
        if (tagWeights == null || tagWeights.isEmpty()) {
            return recommendHot(exclude, safeLimit, currentUserIdNullable);
        }
        try {
            co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> resp = es.search(s -> s
                            .index(INDEX)
                            .size(safeLimit)
                            .query(q -> q.functionScore(fs -> fs
                                    .query(inner -> inner.bool(b -> {
                                        b.filter(f -> f.term(t -> t.field("status").value("published")));
                                        applyExcludeIds(b, exclude);
                                        return b;
                                    }))
                                    .functions(buildTagWeightFunctions(tagWeights))
                                    .scoreMode(FunctionScoreMode.Sum)
                                    .boostMode(FunctionBoostMode.Replace)))
                            .sort(so -> so.field(f -> f.field("_score").order(SortOrder.Desc)))
                            .sort(so -> so.field(f -> f.field("publish_time").order(SortOrder.Desc))),
                    (Class<Map<String, Object>>) (Class<?>) Map.class);
            return hitMapper.mapPostHits(resp.hits().hits(), currentUserIdNullable);
        } catch (Exception e) {
            log.warn("recommendByInterest failed: {}", e.getMessage(), e);
            return recommendHot(exclude, safeLimit, currentUserIdNullable);
        }
    }

    @SuppressWarnings("unchecked")
    public List<FeedItemResponse> recommendHot(Collection<Long> excludePostIds,
                                               int limit,
                                               Long currentUserIdNullable) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        Set<Long> exclude = normalizeExcludeIds(excludePostIds);
        try {
            co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> resp = es.search(s -> s
                            .index(INDEX)
                            .size(safeLimit)
                            .query(q -> q.bool(b -> {
                                b.filter(f -> f.term(t -> t.field("status").value("published")));
                                applyExcludeIds(b, exclude);
                                return b;
                            }))
                            .sort(so -> so.field(f -> f.field("like_count").order(SortOrder.Desc)))
                            .sort(so -> so.field(f -> f.field("publish_time").order(SortOrder.Desc))),
                    (Class<Map<String, Object>>) (Class<?>) Map.class);
            return hitMapper.mapPostHits(resp.hits().hits(), currentUserIdNullable);
        } catch (Exception e) {
            log.warn("recommendHot failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    public List<FeedItemResponse> recommendSimilarToPost(long sourcePostId,
                                                         Collection<Long> excludePostIds,
                                                         int limit,
                                                         Long currentUserIdNullable) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        Set<Long> exclude = normalizeExcludeIds(excludePostIds);
        exclude.add(sourcePostId);
        List<String> tags = loadPostTags(sourcePostId);
        try {
            co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> resp = es.search(s -> s
                            .index(INDEX)
                            .size(safeLimit)
                            .query(q -> q.bool(b -> {
                                b.filter(f -> f.term(t -> t.field("status").value("published")));
                                applyExcludeIds(b, exclude);
                                if (!tags.isEmpty()) {
                                    b.should(sh -> sh.terms(t -> t.field("tags")
                                            .terms(tv -> tv.value(tags.stream().map(FieldValue::of).toList()))));
                                }
                                b.should(sh -> sh.terms(t -> t
                                        .field("contentAnalysis.relatedPostIds")
                                        .terms(tv -> tv.value(List.of(FieldValue.of(sourcePostId))))));
                                b.minimumShouldMatch("1");
                                return b;
                            }))
                            .sort(so -> so.field(f -> f.field("like_count").order(SortOrder.Desc))),
                    (Class<Map<String, Object>>) (Class<?>) Map.class);
            return hitMapper.mapPostHits(resp.hits().hits(), currentUserIdNullable);
        } catch (Exception e) {
            log.warn("recommendSimilarToPost failed sourcePostId={}: {}", sourcePostId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private List<FunctionScore> buildTagWeightFunctions(Map<String, Double> tagWeights) {
        List<FunctionScore> functions = new ArrayList<>();
        for (Map.Entry<String, Double> entry : tagWeights.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            double weight = entry.getValue() * 10.0;
            functions.add(FunctionScore.of(fn -> fn
                    .filter(f -> f.term(t -> t.field("tags").value(entry.getKey())))
                    .weight(weight)));
        }
        if (functions.isEmpty()) {
            functions.add(FunctionScore.of(fn -> fn.filter(f -> f.matchAll(m -> m)).weight(1.0)));
        }
        return functions;
    }

    @SuppressWarnings("unchecked")
    private List<String> loadPostTags(long postId) {
        try {
            GetResponse<Map> response = es.get(g -> g.index(INDEX).id(String.valueOf(postId)), Map.class);
            if (!response.found() || response.source() == null) {
                return List.of();
            }
            return asStringList(response.source().get("tags"));
        } catch (Exception e) {
            log.debug("loadPostTags failed postId={}: {}", postId, e.getMessage());
            return List.of();
        }
    }

    private static Set<Long> normalizeExcludeIds(Collection<Long> excludePostIds) {
        Set<Long> exclude = new HashSet<>();
        if (excludePostIds != null) {
            excludePostIds.stream().filter(id -> id != null && id > 0).forEach(exclude::add);
        }
        return exclude;
    }

    private static void applyExcludeIds(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder builder,
                                        Set<Long> exclude) {
        if (exclude == null || exclude.isEmpty()) {
            return;
        }
        builder.mustNot(mn -> mn.ids(i -> i.values(exclude.stream().map(String::valueOf).toList())));
    }


    private List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}

