package com.chtholly.search.service;

import java.util.Locale;

/**
 * Supported ordering modes for full-text post search.
 */
public enum SearchSort {
    RELEVANCE,
    NEWEST;

    /**
     * Parses a request value, defaulting unknown or missing values to relevance.
     *
     * @param value raw request value
     * @return resolved search ordering mode
     */
    public static SearchSort from(String value) {
        if (value == null || value.isBlank()) {
            return RELEVANCE;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (SearchSort candidate : values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        return RELEVANCE;
    }
}
