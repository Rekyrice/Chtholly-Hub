package com.chtholly.agent.web;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Immutable provider response containing search results and HTTP diagnostics.
 *
 * @param requestUrl requested provider URL, or null when unavailable from a legacy provider
 * @param finalUrl final provider URL, or null when unavailable from a legacy provider
 * @param statusCode HTTP status, or -1 when unavailable
 * @param contentType normalized response content type, or an empty string when unavailable
 * @param bodyBytes bounded response body size, or -1 when unavailable
 * @param redirectChain requested provider URLs including initial and final URLs
 * @param results normalized provider results
 */
public record WebSearchResponse(
        URI requestUrl,
        URI finalUrl,
        int statusCode,
        String contentType,
        int bodyBytes,
        List<URI> redirectChain,
        List<WebSearchResult> results) {

    /**
     * Validates and defensively copies provider response values.
     */
    public WebSearchResponse {
        if (statusCode < -1) {
            throw new IllegalArgumentException("statusCode must be -1 or a non-negative HTTP status");
        }
        if (bodyBytes < -1) {
            throw new IllegalArgumentException("bodyBytes must be -1 or non-negative");
        }
        contentType = Objects.requireNonNull(contentType, "contentType");
        redirectChain = List.copyOf(Objects.requireNonNull(redirectChain, "redirectChain"));
        results = List.copyOf(Objects.requireNonNull(results, "results"));
    }

    /**
     * Wraps results from a legacy provider that cannot expose HTTP metadata.
     *
     * @param results provider results
     * @return results-only response with explicit unavailable metadata sentinels
     */
    public static WebSearchResponse resultsOnly(List<WebSearchResult> results) {
        return new WebSearchResponse(null, null, -1, "", -1, List.of(), results);
    }
}
