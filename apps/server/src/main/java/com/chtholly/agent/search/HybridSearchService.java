package com.chtholly.agent.search;

import com.chtholly.agent.anchor.KnowledgeService;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.llm.rag.RagQueryService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.search.service.SearchService;
import com.chtholly.search.service.SearchSort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Combines semantic, keyword, and entity-to-article retrieval at article identity. */
@Slf4j
@Service
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class HybridSearchService {

    private static final int RRF_K = 60;
    private static final String SEMANTIC = "semantic";
    private static final String KEYWORD = "keyword";
    private static final String ENTITY = "entity";

    private final RagQueryService ragService;
    private final SearchService searchService;
    private final KnowledgeService knowledgeService;
    private final PostMapper postMapper;

    public HybridSearchService(RagQueryService ragService,
                               SearchService searchService,
                               KnowledgeService knowledgeService,
                               PostMapper postMapper) {
        this.ragService = ragService;
        this.searchService = searchService;
        this.knowledgeService = knowledgeService;
        this.postMapper = postMapper;
    }

    /** Runs all three retrievers, authorizes candidates in MySQL, then applies document-level RRF. */
    public HybridSearchResponse hybridSearch(String query, int topK) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return HybridSearchResponse.empty();
        }
        int safeTopK = Math.min(topK, 20);
        int fetchK = safeTopK * 2;

        SearchOutcome semanticRaw = safeSearch(() -> ragService.search(query, fetchK), SEMANTIC);
        SearchOutcome keywordRaw = safeSearch(() -> keywordSearch(query, fetchK, KEYWORD), KEYWORD);
        SearchOutcome entityRaw = safeSearch(
                () -> entityArticleSearch(query, safeTopK, fetchK), ENTITY);

        AuthoritySnapshot authority = loadAuthority(semanticRaw, keywordRaw, entityRaw);
        SearchOutcome semantic = authorize(semanticRaw, authority);
        SearchOutcome keyword = authorize(keywordRaw, authority);
        SearchOutcome entity = authorize(entityRaw, authority);

        List<SearchResult> semanticDocuments = aggregateByDocument(semantic.results());
        List<SearchResult> keywordDocuments = aggregateByDocument(keyword.results());
        List<SearchResult> entityDocuments = aggregateByDocument(entity.results());
        Map<String, SearchResult> merged = mergeResults(
                semanticDocuments, keywordDocuments, entityDocuments);
        List<SearchResult> documents = applyRrf(
                merged, semanticDocuments, keywordDocuments, entityDocuments).stream()
                .limit(safeTopK)
                .toList();

        return new HybridSearchResponse(
                documents,
                Map.of(
                        SEMANTIC, semantic.status(),
                        KEYWORD, keyword.status(),
                        ENTITY, entity.status()));
    }

    /** Keeps the first chunk for display while allowing one vote per article per route. */
    private List<SearchResult> aggregateByDocument(List<SearchResult> rankedChunks) {
        Map<String, SearchResult> documents = new LinkedHashMap<>();
        for (SearchResult result : rankedChunks) {
            if (result == null || !StringUtils.hasText(result.getDocumentId())) {
                continue;
            }
            documents.putIfAbsent(result.getDocumentId(), result);
        }
        return List.copyOf(documents.values());
    }

    @SafeVarargs
    private final Map<String, SearchResult> mergeResults(List<SearchResult>... resultLists) {
        Map<String, SearchResult> merged = new LinkedHashMap<>();
        for (List<SearchResult> resultList : resultLists) {
            for (SearchResult result : resultList) {
                if (result != null && StringUtils.hasText(result.getDocumentId())) {
                    merged.putIfAbsent(result.getDocumentId(), result);
                }
            }
        }
        return merged;
    }

    @SafeVarargs
    private final List<SearchResult> applyRrf(
            Map<String, SearchResult> merged, List<SearchResult>... resultLists) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Set<String>> sources = new LinkedHashMap<>();
        for (List<SearchResult> list : resultLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                SearchResult result = list.get(rank);
                if (result != null && StringUtils.hasText(result.getDocumentId())) {
                    scores.merge(result.getDocumentId(), 1.0 / (RRF_K + rank + 1), Double::sum);
                    if (StringUtils.hasText(result.getSource())) {
                        sources.computeIfAbsent(result.getDocumentId(), ignored -> new LinkedHashSet<>())
                                .add(result.getSource());
                    }
                }
            }
        }
        for (Map.Entry<String, SearchResult> entry : merged.entrySet()) {
            entry.getValue().setScore(scores.getOrDefault(entry.getKey(), 0.0));
            entry.getValue().setSource(String.join("+", sources.getOrDefault(entry.getKey(), Set.of())));
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(SearchResult::getScore).reversed()
                        .thenComparingLong(result -> {
                            Long articleId = parsePostId(result.getId());
                            return articleId == null ? Long.MAX_VALUE : articleId;
                        })
                        .thenComparing(SearchResult::getId))
                .toList();
    }

    private List<SearchResult> entityArticleSearch(String query, int entityTopK, int fetchK) {
        List<SearchResult> entities = knowledgeService.searchEntities(query, entityTopK);
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        List<String> names = entities.stream()
                .filter(java.util.Objects::nonNull)
                .map(SearchResult::getTitle)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (names.isEmpty()) {
            return List.of();
        }
        PageResponse<FeedItemResponse> response = searchService.searchByEntityNames(names, fetchK, null);
        if (response == null || Boolean.TRUE.equals(response.degraded())) {
            throw new IllegalStateException("entity search degraded");
        }
        if (response.items() == null || response.items().isEmpty()) {
            return List.of();
        }
        List<SearchResult> results = new ArrayList<>(response.items().size());
        for (FeedItemResponse item : response.items()) {
            if (item != null && StringUtils.hasText(normalizePostId(item.id()))) {
                String id = normalizePostId(item.id());
                results.add(new SearchResult(id, item.title(), item.description(), ENTITY, 0.0));
            }
        }
        return List.copyOf(results);
    }

    private List<SearchResult> keywordSearch(String query, int fetchK, String source) {
        PageResponse<FeedItemResponse> response = searchService.search(
                query, fetchK, null, null, SearchSort.RELEVANCE, null);
        if (response == null || Boolean.TRUE.equals(response.degraded())) {
            throw new IllegalStateException(source + " search degraded");
        }
        if (response.items() == null || response.items().isEmpty()) {
            return List.of();
        }
        List<SearchResult> results = new ArrayList<>(response.items().size());
        for (FeedItemResponse item : response.items()) {
            if (item == null) {
                continue;
            }
            String id = normalizePostId(item.id());
            if (StringUtils.hasText(id)) {
                results.add(new SearchResult(id, item.title(), item.description(), source, 0.0));
            }
        }
        return List.copyOf(results);
    }

    private SearchOutcome safeSearch(SearchSupplier supplier, String source) {
        try {
            List<SearchResult> results = supplier.get();
            List<SearchResult> normalized = results == null
                    ? List.of()
                    : results.stream().filter(java.util.Objects::nonNull).toList();
            return new SearchOutcome(
                    normalized,
                    normalized.isEmpty() ? RetrievalStatus.SUCCESS_EMPTY : RetrievalStatus.SUCCESS_RESULTS);
        } catch (Exception exception) {
            log.warn("Hybrid {} search failed: {}", source, exception.getMessage());
            return new SearchOutcome(
                    List.of(), isTimeout(exception) ? RetrievalStatus.TIMEOUT : RetrievalStatus.FAILED);
        }
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException
                    || current.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT)
                    .contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private AuthoritySnapshot loadAuthority(SearchOutcome... outcomes) {
        Set<Long> ids = new LinkedHashSet<>();
        for (SearchOutcome outcome : outcomes) {
            for (SearchResult result : outcome.results()) {
                Long id = parsePostId(result == null ? null : result.getId());
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty()) {
            return new AuthoritySnapshot(Map.of(), false);
        }
        try {
            List<Post> posts = postMapper.findByIds(List.copyOf(ids));
            Map<Long, Post> byId = new LinkedHashMap<>();
            if (posts != null) {
                for (Post post : posts) {
                    if (post != null && post.getId() != null) {
                        byId.put(post.getId(), post);
                    }
                }
            }
            return new AuthoritySnapshot(Map.copyOf(byId), false);
        } catch (RuntimeException exception) {
            log.warn("Hybrid search authority lookup failed: {}", exception.getMessage());
            return new AuthoritySnapshot(Map.of(), true);
        }
    }

    private SearchOutcome authorize(SearchOutcome outcome, AuthoritySnapshot authority) {
        if (outcome.status() == RetrievalStatus.FAILED || outcome.results().isEmpty()) {
            return outcome;
        }
        if (authority.failed()) {
            return new SearchOutcome(List.of(), RetrievalStatus.FAILED);
        }
        List<SearchResult> authorized = outcome.results().stream()
                .map(result -> authorize(result, authority.posts()))
                .filter(java.util.Objects::nonNull)
                .toList();
        return new SearchOutcome(
                authorized,
                authorized.isEmpty() ? RetrievalStatus.SUCCESS_EMPTY : RetrievalStatus.SUCCESS_RESULTS);
    }

    private SearchResult authorize(SearchResult candidate, Map<Long, Post> posts) {
        Long postId = parsePostId(candidate.getId());
        Post post = postId == null ? null : posts.get(postId);
        if (post == null
                || !"published".equalsIgnoreCase(post.getStatus())
                || !"public".equalsIgnoreCase(post.getVisible())) {
            return null;
        }
        String sourceHash = currentContentHash(post);
        if (!StringUtils.hasText(sourceHash)) {
            return null;
        }
        if (SEMANTIC.equals(candidate.getSource())
                && !semanticFingerprintMatches(candidate, post)) {
            return null;
        }
        String id = "post:" + postId;
        String snippet = SEMANTIC.equals(candidate.getSource())
                ? candidate.getSnippet()
                : post.getDescription();
        String sourceVersion = post.getUpdateTime() == null
                ? "current"
                : post.getUpdateTime().toString();
        return new SearchResult(
                id,
                StringUtils.hasText(post.getTitle()) ? post.getTitle() : candidate.getTitle(),
                snippet,
                candidate.getSource(),
                0.0,
                id,
                SEMANTIC.equals(candidate.getSource()) ? candidate.getChunkId() : null,
                sourceVersion,
                sourceHash,
                Set.of("PUBLIC"));
    }

    private boolean semanticFingerprintMatches(SearchResult candidate, Post post) {
        if (StringUtils.hasText(post.getContentSha256())) {
            return post.getContentSha256().equalsIgnoreCase(candidate.getSourceHash());
        }
        if (StringUtils.hasText(post.getContentEtag())) {
            return post.getContentEtag().equals(candidate.getSourceHash())
                    || post.getContentEtag().equals(candidate.getSourceVersion());
        }
        return false;
    }

    private String currentContentHash(Post post) {
        if (StringUtils.hasText(post.getContentSha256())) {
            return post.getContentSha256();
        }
        return StringUtils.hasText(post.getContentEtag()) ? post.getContentEtag() : null;
    }

    private static Long parsePostId(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        String value = id.startsWith("post:") ? id.substring(5) : id;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalizePostId(String id) {
        Long parsed = parsePostId(id == null ? null : id.trim());
        return parsed == null ? "" : "post:" + parsed;
    }

    @FunctionalInterface
    private interface SearchSupplier {
        List<SearchResult> get();
    }

    public enum RetrievalStatus {
        SUCCESS_RESULTS,
        SUCCESS_EMPTY,
        TIMEOUT,
        FAILED
    }

    public record HybridSearchResponse(
            List<SearchResult> documents,
            Map<String, RetrievalStatus> statuses) {

        public HybridSearchResponse {
            documents = documents == null ? List.of() : List.copyOf(documents);
            statuses = statuses == null ? Map.of() : Map.copyOf(statuses);
        }

        public boolean degraded() {
            return statuses.containsValue(RetrievalStatus.FAILED)
                    || statuses.containsValue(RetrievalStatus.TIMEOUT);
        }

        static HybridSearchResponse empty() {
            return new HybridSearchResponse(List.of(), Map.of());
        }
    }

    private record SearchOutcome(List<SearchResult> results, RetrievalStatus status) {
    }

    private record AuthoritySnapshot(Map<Long, Post> posts, boolean failed) {
    }
}
