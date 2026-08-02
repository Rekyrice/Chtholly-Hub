package com.chtholly.agent.web;

/**
 * One provider search result without evidence lifecycle state.
 *
 * @param title result title
 * @param url canonical target URL text
 * @param snippet result summary
 * @param rank one-based organic rank
 */
public record WebSearchResult(String title, String url, String snippet, int rank) {
}
