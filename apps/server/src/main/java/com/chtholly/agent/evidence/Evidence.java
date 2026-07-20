package com.chtholly.agent.evidence;

import com.chtholly.agent.search.SearchResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/** Immutable, version-bound source material that may support one Agent turn. */
public record Evidence(
        String evidenceId,
        String sourceType,
        String sourceId,
        String documentId,
        String chunkId,
        String title,
        String retrievalSource,
        String sourceVersion,
        String sourceHash,
        String excerpt,
        int rank,
        double trust,
        Set<String> permissions,
        String citationId) {

    public Evidence {
        evidenceId = required(evidenceId, "evidenceId");
        sourceType = required(sourceType, "sourceType");
        sourceId = required(sourceId, "sourceId");
        documentId = required(documentId, "documentId");
        title = required(title, "title");
        retrievalSource = required(retrievalSource, "retrievalSource");
        sourceVersion = required(sourceVersion, "sourceVersion");
        sourceHash = required(sourceHash, "sourceHash");
        excerpt = required(excerpt, "excerpt");
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be positive");
        }
        if (trust < 0.0 || trust > 1.0) {
            throw new IllegalArgumentException("trust must be between 0 and 1");
        }
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        citationId = required(citationId, "citationId");
    }

    Evidence withRankAndCitation(int newRank, String newCitationId) {
        return new Evidence(
                evidenceId, sourceType, sourceId, documentId, chunkId,
                title, retrievalSource, sourceVersion, sourceHash, excerpt,
                newRank, trust, permissions, newCitationId);
    }

    /** Converts one authorized article-level retrieval result into the evidence contract. */
    public static Evidence fromSearchResult(SearchResult result, int rank) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        String sourceId = required(result.getId(), "sourceId");
        String documentId = required(result.getDocumentId(), "documentId");
        String title = required(result.getTitle(), "title");
        String source = required(result.getSource(), "retrievalSource");
        String version = required(result.getSourceVersion(), "sourceVersion");
        String hash = required(result.getSourceHash(), "sourceHash");
        String excerpt = required(result.getSnippet(), "excerpt");
        String evidenceId = "ev-" + sha256(String.join("|",
                sourceId,
                documentId,
                result.getChunkId() == null ? "" : result.getChunkId(),
                version,
                hash)).substring(0, 24);
        double trust = source.contains("keyword") && source.contains("semantic")
                ? 0.90
                : source.contains("keyword") ? 0.85 : source.contains("semantic") ? 0.75 : 0.70;
        return new Evidence(
                evidenceId,
                "POST",
                sourceId,
                documentId,
                result.getChunkId(),
                title,
                source,
                version,
                hash,
                excerpt,
                rank,
                trust,
                result.getPermissions(),
                "E" + rank);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
