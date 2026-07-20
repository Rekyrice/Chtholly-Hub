package com.chtholly.agent.search;

import java.util.Set;

/**
 * Unified search result used by the agent's hybrid retrieval layer.
 */
public class SearchResult {

    private final String id;
    private final String title;
    private final String snippet;
    private String source;
    private final String documentId;
    private final String chunkId;
    private final String sourceVersion;
    private final String sourceHash;
    private final Set<String> permissions;
    private double score;

    public SearchResult(String id, String title, String snippet, String source, double score) {
        this(id, title, snippet, source, score, id, null, null, null, Set.of());
    }

    public SearchResult(
            String id,
            String title,
            String snippet,
            String source,
            double score,
            String documentId,
            String chunkId,
            String sourceVersion,
            String sourceHash,
            Set<String> permissions) {
        this.id = id;
        this.title = title;
        this.snippet = snippet;
        this.source = source;
        this.score = score;
        this.documentId = documentId;
        this.chunkId = chunkId;
        this.sourceVersion = sourceVersion;
        this.sourceHash = sourceHash;
        this.permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
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

    public void setSource(String source) {
        this.source = source;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getChunkId() {
        return chunkId;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public String getSourceHash() {
        return sourceHash;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }
}
