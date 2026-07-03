package com.chtholly.agent.search;

import com.chtholly.agent.anchor.KnowledgeService;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.llm.rag.RagQueryService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.search.service.SearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines semantic, keyword, and entity search for agent context retrieval.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class HybridSearchService {

    private static final int RRF_K = 60;

    private final RagQueryService ragService;
    private final SearchService searchService;
    private final KnowledgeService knowledgeService;

    public HybridSearchService(RagQueryService ragService,
                               SearchService searchService,
                               KnowledgeService knowledgeService) {
        this.ragService = ragService;
        this.searchService = searchService;
        this.knowledgeService = knowledgeService;
    }

    /**
     * Runs hybrid search and fuses the ranked lists with Reciprocal Rank Fusion.
     *
     * @param query Query text.
     * @param topK  Maximum result count.
     * @return Ranked hybrid results.
     */
    public List<SearchResult> hybridSearch(String query, int topK) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        int safeTopK = Math.min(topK, 20);
        int fetchK = safeTopK * 2;

        List<SearchResult> semantic = safeSearch(() -> ragService.search(query, fetchK), "semantic");
        List<SearchResult> keyword = safeSearch(() -> keywordSearch(query, fetchK), "keyword");
        List<SearchResult> entity = safeSearch(() -> knowledgeService.searchEntities(query, safeTopK), "entity");

        Map<String, SearchResult> merged = mergeResults(semantic, keyword, entity);
        return applyRrf(merged, semantic, keyword, entity).stream()
                .limit(safeTopK)
                .toList();
    }

    @SafeVarargs
    private final Map<String, SearchResult> mergeResults(List<SearchResult>... resultLists) {
        Map<String, SearchResult> merged = new LinkedHashMap<>();
        for (List<SearchResult> resultList : resultLists) {
            if (resultList == null) {
                continue;
            }
            for (SearchResult result : resultList) {
                if (result == null || !StringUtils.hasText(result.getId())) {
                    continue;
                }
                merged.putIfAbsent(result.getId(), result);
            }
        }
        return merged;
    }

    @SafeVarargs
    private final List<SearchResult> applyRrf(Map<String, SearchResult> merged, List<SearchResult>... resultLists) {
        Map<String, Integer> originalOrder = new LinkedHashMap<>();
        int order = 0;
        for (String id : merged.keySet()) {
            originalOrder.put(id, order++);
        }

        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<SearchResult> list : resultLists) {
            if (list == null) {
                continue;
            }
            for (int rank = 0; rank < list.size(); rank++) {
                SearchResult result = list.get(rank);
                if (result == null || !StringUtils.hasText(result.getId())) {
                    continue;
                }
                scores.merge(result.getId(), 1.0 / (RRF_K + rank + 1), Double::sum);
            }
        }

        for (SearchResult result : merged.values()) {
            result.setScore(scores.getOrDefault(result.getId(), 0.0));
        }

        return merged.values().stream()
                .sorted(Comparator.comparingDouble(SearchResult::getScore).reversed()
                        .thenComparingInt(result -> originalOrder.getOrDefault(result.getId(), Integer.MAX_VALUE)))
                .toList();
    }

    private List<SearchResult> keywordSearch(String query, int fetchK) {
        PageResponse<FeedItemResponse> response = searchService.search(query, fetchK, null, null, null);
        if (response == null || response.items() == null || response.items().isEmpty()) {
            return List.of();
        }
        List<SearchResult> results = new ArrayList<>(response.items().size());
        for (FeedItemResponse item : response.items()) {
            if (item == null) {
                continue;
            }
            String id = normalizePostId(item.id());
            results.add(new SearchResult(
                    id,
                    item.title(),
                    item.description(),
                    "keyword",
                    0.0));
        }
        return List.copyOf(results);
    }

    private List<SearchResult> safeSearch(SearchSupplier supplier, String source) {
        try {
            List<SearchResult> results = supplier.get();
            return results == null ? List.of() : results;
        } catch (Exception e) {
            log.warn("Hybrid {} search failed: {}", source, e.getMessage());
            return List.of();
        }
    }

    private static String normalizePostId(String id) {
        if (!StringUtils.hasText(id)) {
            return "";
        }
        String trimmed = id.trim();
        return trimmed.startsWith("post:") ? trimmed : "post:" + trimmed;
    }

    @FunctionalInterface
    private interface SearchSupplier {
        List<SearchResult> get();
    }
}
