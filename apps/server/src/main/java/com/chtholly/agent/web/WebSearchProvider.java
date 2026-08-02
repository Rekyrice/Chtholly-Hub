package com.chtholly.agent.web;

import java.util.List;

/**
 * Searches the public web and returns provider-level results without creating evidence.
 */
public interface WebSearchProvider {

    /**
     * Searches for up to five results.
     *
     * @param query search query
     * @return provider results
     */
    default List<WebSearchResult> search(String query) {
        return search(query, 5);
    }

    /**
     * Searches for a bounded number of results.
     *
     * @param query search query
     * @param limit requested result count, capped at eight
     * @return provider results
     */
    List<WebSearchResult> search(String query, int limit);

    /**
     * Searches with provider HTTP diagnostics when available.
     *
     * <p>Legacy providers remain compatible through a results-only default response.</p>
     *
     * @param query search query
     * @param limit requested result count, capped at eight
     * @return immutable search results and provider response metadata
     */
    default WebSearchResponse searchDetailed(String query, int limit) {
        return WebSearchResponse.resultsOnly(search(query, limit));
    }
}
