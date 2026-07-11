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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    /**
     * ES 索引名：Chtholly Hub 内容统一索引。
     */
    private static final String INDEX = "chtholly_content_index";
    private static final String EXPERIENCE_INDEX = "experiences";
    private static final String STATUS_OK = "ok";
    private static final String STATUS_DEGRADED = "degraded";
    private static final String HOT_TAGS_AGG = "hot_tags";
    private static final int DEFAULT_HUB_PAGE = 1;
    private static final int DEFAULT_HUB_PAGE_SIZE = 8;
    private static final int MAX_HUB_PAGE_SIZE = 50;

    private enum HubRegion {
        LATEST_POSTS,
        HOT_TAGS,
        RECOMMENDATIONS,
        EXPERIENCES
    }

    /**
     * Full-text search with relevance ranking, highlights, and search_after cursor pagination.
     *
     * @param q                     Query string.
     * @param size                  Page size.
     * @param tagsCsv               Optional comma-separated tag filter.
     * @param after                 Opaque cursor from previous page (Base64-encoded sort values).
     * @param currentUserIdNullable Current user for liked/faved enrichment.
     * @return Search results; empty with {@code degraded=true} on ES failure.
     */
    @SuppressWarnings("unchecked")
    public PageResponse<FeedItemResponse> search(String q, int size, String tagsCsv, String after, Long currentUserIdNullable) {
        int safeSize = Pagination.clampSize(size);
        List<String> tags = parseCsv(tagsCsv);
        List<FieldValue> afterValues = parseAfter(after);

        // 复合排序：优先相关性，其次发布时间与互动数据，最后按 content_id 稳定排序
        List<SortOptions> sorts = new ArrayList<>();
        sorts.add(SortOptions.of(s -> s.score(o -> o.order(SortOrder.Desc))));
        sorts.add(SortOptions.of(s -> s.field(f -> f.field("publish_time").order(SortOrder.Desc))));
        sorts.add(SortOptions.of(s -> s.field(f -> f.field("like_count").order(SortOrder.Desc))));
        sorts.add(SortOptions.of(s -> s.field(f -> f.field("view_count").order(SortOrder.Desc))));
        sorts.add(SortOptions.of(s -> s.field(f -> f.field("content_id").order(SortOrder.Desc))));

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
                nextCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(String.join(",", parts).getBytes());
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
    @SuppressWarnings("unchecked")
    @Override
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

    @SuppressWarnings("unchecked")
    @Override
    public List<FeedItemResponse> recommendByInterest(Map<String, Double> tagWeights,
                                                      Collection<Long> excludePostIds,
                                                      int limit,
                                                      Long currentUserIdNullable) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_HUB_PAGE_SIZE));
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
    @Override
    public List<FeedItemResponse> recommendHot(Collection<Long> excludePostIds,
                                               int limit,
                                               Long currentUserIdNullable) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_HUB_PAGE_SIZE));
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
    @Override
    public List<FeedItemResponse> recommendSimilarToPost(long sourcePostId,
                                                         Collection<Long> excludePostIds,
                                                         int limit,
                                                         Long currentUserIdNullable) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_HUB_PAGE_SIZE));
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
    private List<FieldValue> parseAfter(String after) {
        if (after == null || after.isBlank()) {
            return null;
        }

        try {
            String decoded = new String(Base64.getUrlDecoder().decode(after));
            String[] parts = decoded.split(",");
            List<FieldValue> out = new ArrayList<>(parts.length);

            for (int i = 0; i < parts.length; i++) {
                String p = parts[i];
                if (i == 0) {
                    out.add(FieldValue.of(Double.parseDouble(p)));
                } else if (i == 1) {
                    out.add(FieldValue.of(Long.parseLong(p)));
                } else {
                    out.add(FieldValue.of(Long.parseLong(p)));
                }
            }

            return out;
        } catch (Exception e) {
            log.warn("Search cursor parse failed, after={}: {}", after, e.getMessage());
            return null;
        }
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

    private record RegionResult<T>(List<T> items, String status, int total) {
        private static <T> RegionResult<T> degraded() {
            return new RegionResult<>(Collections.emptyList(), STATUS_DEGRADED, 0);
        }
    }
}
