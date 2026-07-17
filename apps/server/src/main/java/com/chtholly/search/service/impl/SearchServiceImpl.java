package com.chtholly.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch.core.MsearchResponse;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchItem;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem;
import co.elastic.clients.elasticsearch.core.msearch.RequestItem;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.search.CompletionSuggestOption;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.Suggestion;
import com.chtholly.agent.api.dto.AgentExperienceResponse;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.common.api.pagination.Pagination;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.search.api.dto.HubFeedResponse;
import com.chtholly.search.api.dto.SuggestResponse;
import com.chtholly.search.api.dto.TagCountResponse;
import com.chtholly.search.service.SearchService;
import com.chtholly.search.service.SearchSort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Elasticsearch full-text search with function_score boosting and cursor pagination.
 *
 * <p>Query strategy: CJK uses match_phrase (avoid single-char false positives from standard analyzer);
 * Latin uses multi_match with AND operator. Interaction counts weighted via log1p field_value_factor.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchServiceImpl implements SearchService {

    private final ElasticsearchClient es;
    private final SearchHitMapper hitMapper;
    private final HubFeedSearchService hubFeedSearchService;
    private final PostRecommendationSearchService recommendationSearchService;
    /**
     * ES 索引名：Chtholly Hub 内容统一索引。
     */
    private static final String INDEX = "chtholly_content_index";
    private static final int MAX_HUB_PAGE_SIZE = 50;

    /**
     * Full-text search with relevance ranking, highlights, and search_after cursor pagination.
     *
     * @param q                     Query string.
     * @param size                  Page size.
     * @param tagsCsv               Optional comma-separated tag filter.
     * @param after                 Opaque cursor from previous page (Base64-encoded sort values).
     * @param sort                  Requested result ordering; null defaults to relevance.
     * @param currentUserIdNullable Current user for liked/faved enrichment.
     * @return Search results; empty with {@code degraded=true} on ES failure.
     */
    @SuppressWarnings("unchecked")
    @Override
    public PageResponse<FeedItemResponse> search(
            String q, int size, String tagsCsv, String after,
            SearchSort sort, Long currentUserIdNullable) {
        int safeSize = Pagination.clampSize(size);
        List<String> tags = parseCsv(tagsCsv);
        SearchSort resolvedSort = sort == null ? SearchSort.RELEVANCE : sort;
        List<FieldValue> afterValues = parseAfter(after, resolvedSort);
        List<SortOptions> sorts = sortsFor(resolvedSort);

        // 完整包名，不然和自定义的 SearchResponse 冲突
        co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> resp;
        try {
            resp = es.search(s -> {
                var b = s.index(INDEX)
                        .size(safeSize)
                        // 召回与加权：先构造 bool 查询，再用 function_score 做互动数据加权
                        .query(qb -> qb.functionScore(fs -> fs
                                .query(qb2 -> qb2.bool(bq -> {
                                    // 中文：短语匹配（standard 分词会把单字拆开导致误命中）
                                    // 英文/数字：multi_match 且要求所有词都出现
                                    if (containsCjk(q)) {
                                        bq.must(m -> m.bool(inner -> inner
                                                .should(sh -> sh.matchPhrase(mp -> mp.field("title").query(q).boost(3.0f)))
                                                .should(sh -> sh.matchPhrase(mp -> mp.field("body").query(q)))
                                                .minimumShouldMatch("1")
                                        ));
                                    } else {
                                        bq.must(m -> m.multiMatch(mm -> mm.query(q)
                                                .fields("title^3", "body")
                                                .operator(Operator.And)));
                                    }
                                    bq.filter(f -> f.term(t -> t.field("status")
                                            .value(v -> v.stringValue("published"))));

                                    if (tags != null && !tags.isEmpty()) {
                                        bq.filter(f -> f.terms(t -> t.field("tags")
                                                .terms(tv -> tv.value(tags.stream().map(FieldValue::of).toList()))));
                                    }
                                    return bq;
                                }))
                                // title^3：标题命中权重是正文 3 倍，符合用户「搜标题」的预期
                                .functions(fn -> fn.fieldValueFactor(fvf -> fvf.field("like_count")
                                        .modifier(FieldValueFactorModifier.Log1p))
                                        .weight(2.0))
                                // log1p 修饰：点赞 1 与 1000 的差距被压缩，避免头部内容完全碾压相关性
                                .functions(fn -> fn.fieldValueFactor(fvf -> fvf.field("view_count")
                                        .modifier(FieldValueFactorModifier.Log1p))
                                        .weight(1.0))
                                .boostMode(FunctionBoostMode.Sum)
                        ))
                        // 只取一小段高亮摘要，避免把整篇 Markdown 正文塞进列表
                        .highlight(h -> h
                                .fields("title", f -> f.numberOfFragments(1).fragmentSize(80))
                                .fields("body", f -> f.numberOfFragments(1).fragmentSize(160))
                        )
                        .sort(sorts);
                // 游标分页：携带上一次最后命中的 sort 值
                if (afterValues != null && !afterValues.isEmpty()) {
                    b = b.searchAfter(afterValues);
                }

                return b;
            }, (Class<Map<String, Object>>)(Class<?>) Map.class);
        } catch (Exception e) {
            log.error("Search failed for query: {}", q, e);
            return new PageResponse<>(Collections.emptyList(), 0, safeSize, 0L, false, null, true);
        }

        List<Hit<Map<String, Object>>> hits =
                resp.hits() == null ? Collections.emptyList() : resp.hits().hits();
        List<FeedItemResponse> items = hitMapper.mapPostHits(hits, currentUserIdNullable);

        String nextCursor = null;
        boolean hasMore = items.size() >= safeSize;

        if (!hits.isEmpty()) {
            List<FieldValue> sv = hits.getLast().sort();
            if (sv != null && !sv.isEmpty()) {
                List<String> parts = sv.stream().map(this::fieldValueToString).collect(Collectors.toList());
                nextCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        String.join(",", parts).getBytes(StandardCharsets.UTF_8));
            }
        }

        return new PageResponse<>(items, 0, safeSize, 0L, hasMore, nextCursor, false);
    }

    /**
     * Aggregates Hub search regions with one Elasticsearch msearch request.
     *
     * @param interestTags Optional comma-separated user interest tags for recommendations.
     * @param currentUserIdNullable Current user for liked/faved enrichment.
     * @return Hub payload with per-region status for partial failure handling.
     */
    @Override
    public HubFeedResponse hubFeed(String interestTags, Long currentUserIdNullable, int page, int size) {
        return hubFeedSearchService.hubFeed(interestTags, currentUserIdNullable, page, size);
    }

    @Override
    public List<FeedItemResponse> recommendByInterest(
            Map<String, Double> tagWeights, Collection<Long> excludePostIds,
            int limit, Long currentUserIdNullable) {
        return recommendationSearchService.recommendByInterest(
                tagWeights, excludePostIds, limit, currentUserIdNullable);
    }

    @Override
    public List<FeedItemResponse> recommendHot(
            Collection<Long> excludePostIds, int limit, Long currentUserIdNullable) {
        return recommendationSearchService.recommendHot(excludePostIds, limit, currentUserIdNullable);
    }

    @Override
    public List<FeedItemResponse> recommendSimilarToPost(
            long sourcePostId, Collection<Long> excludePostIds,
            int limit, Long currentUserIdNullable) {
        return recommendationSearchService.recommendSimilarToPost(
                sourcePostId, excludePostIds, limit, currentUserIdNullable);
    }

    /**
     * Typeahead suggestions via ES Completion Suggester on {@code title_suggest} field.
     *
     * @param prefix Query prefix.
     * @param size   Max suggestions to return.
     * @return Matching title strings (empty on ES failure).
     */
    @SuppressWarnings("unchecked")
    public SuggestResponse suggest(String prefix, int size) {
        co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> resp;
        try {
            resp = es.search(s -> s.index(INDEX)
                    .suggest(sug -> sug.suggesters("title_suggest",
                            sc -> sc.prefix(prefix).completion(c -> c.field("title_suggest").size(size))))
                    , (Class<Map<String, Object>>)(Class<?>) Map.class);
        } catch (Exception e) {
            log.warn("Suggest ES query failed, prefix={}: {}", prefix, e.getMessage(), e);
            return new SuggestResponse(Collections.emptyList());
        }
        List<String> items = new ArrayList<>();
        try {
            var sugg = resp.suggest();
            List<Suggestion<Map<String, Object>>> entry = sugg == null ? null : sugg.get("title_suggest");
            if (entry != null) {
                for (Suggestion<Map<String, Object>> s : entry) {
                    var comp = s.completion();
                    if (comp != null && comp.options() != null) {
                        for (CompletionSuggestOption<Map<String, Object>> opt : comp.options()) {
                            String text = opt.text();
                            if (text != null && !text.isBlank()) {
                                items.add(text);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Suggest response parse failed, prefix={}: {}", prefix, e.getMessage(), e);
        }
        return new SuggestResponse(items);
    }

    private List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }

        String[] parts = csv.split(",");
        List<String> out = new ArrayList<>();

        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }

        }
        return out;
    }

    /**
     * 解析 Base64URL 游标为 sort 值数组，按顺序还原各 FieldValue。
     */
    private List<FieldValue> parseAfter(String after, SearchSort sort) {
        if (after == null || after.isBlank()) {
            return null;
        }

        try {
            String decoded = new String(Base64.getUrlDecoder().decode(after), StandardCharsets.UTF_8);
            String[] parts = decoded.split(",", -1);
            int expectedParts = sort == SearchSort.NEWEST ? 2 : 5;
            if (parts.length != expectedParts) {
                throw new IllegalArgumentException("cursor sort value count does not match ordering");
            }
            List<FieldValue> out = new ArrayList<>(parts.length);

            for (int i = 0; i < parts.length; i++) {
                String p = parts[i];
                if (sort == SearchSort.RELEVANCE && i == 0) {
                    out.add(FieldValue.of(Double.parseDouble(p)));
                } else {
                    out.add(FieldValue.of(Long.parseLong(p)));
                }
            }

            return out;
        } catch (Exception e) {
            log.warn("Search cursor ignored because it is invalid for sort={}", sort);
            return null;
        }
    }

    private List<SortOptions> sortsFor(SearchSort sort) {
        if (sort == SearchSort.NEWEST) {
            return List.of(
                    SortOptions.of(s -> s.field(f -> f.field("publish_time").order(SortOrder.Desc))),
                    SortOptions.of(s -> s.field(f -> f.field("content_id").order(SortOrder.Desc))));
        }
        return List.of(
                SortOptions.of(s -> s.score(o -> o.order(SortOrder.Desc))),
                SortOptions.of(s -> s.field(f -> f.field("publish_time").order(SortOrder.Desc))),
                SortOptions.of(s -> s.field(f -> f.field("like_count").order(SortOrder.Desc))),
                SortOptions.of(s -> s.field(f -> f.field("view_count").order(SortOrder.Desc))),
                SortOptions.of(s -> s.field(f -> f.field("content_id").order(SortOrder.Desc))));
    }

    private boolean containsCjk(String q) {
        if (q == null || q.isBlank()) {
            return false;
        }
        return q.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    /**
     * 将 sort 的 FieldValue 安全转换为字符串，便于编码游标。
     */
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

    private Boolean asBoolean(Object o) {
        switch (o) {
            case null -> {
                return null;
            }
            case Boolean b -> {
                return b;
            }
            case Number n -> {
                return n.intValue() != 0;
            }
            default -> {
            }
        }

        String s = String.valueOf(o).toLowerCase();
        if ("true".equals(s)) {
            return Boolean.TRUE;
        }
        if ("false".equals(s)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 任意对象转 List<String>（支持原生 List 与简单 JSON 数组字符串）。
     */
    private List<String> asStringList(Object o) {
        if (o == null) {
            return Collections.emptyList();
        }

        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object e : l) {
                if (e != null) {
                    out.add(String.valueOf(e));
                }
            }
            return out;
        }

        String s = String.valueOf(o);
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
            if (s.isBlank()) {
                return Collections.emptyList();
            }

            String[] parts = s.split(",");
            List<String> out = new ArrayList<>();
            for (String p : parts) {
                String t = p.trim();
                if (t.startsWith("\"") && t.endsWith("\"")) {
                    t = t.substring(1, t.length() - 1);
                }

                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            return out;
        }
        return Collections.emptyList();
    }

}
