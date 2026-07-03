package com.chtholly.agent.search;

/**
 * Unified search result used by the agent's hybrid retrieval layer.
 */
public class SearchResult {

    private final String id;
    private final String title;
    private final String snippet;
    private final String source;
    private double score;

    public SearchResult(String id, String title, String snippet, String source, double score) {
        this.id = id;
        this.title = title;
        this.snippet = snippet;
        this.source = source;
        this.score = score;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getSnippet() {
        return snippet;
    }

    public String getSource() {
        return source;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }
}
