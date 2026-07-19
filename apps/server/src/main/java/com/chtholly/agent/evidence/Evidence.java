package com.chtholly.agent.evidence;

import java.util.Set;

/** Immutable source material that may support claims in one Agent turn. */
public record Evidence(
        String evidenceId,
        String sourceType,
        String sourceId,
        String documentId,
        String chunkId,
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
        sourceVersion = required(sourceVersion, "sourceVersion");
        sourceHash = required(sourceHash, "sourceHash");
        excerpt = excerpt == null ? "" : excerpt;
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
                sourceVersion, sourceHash, excerpt, newRank, trust, permissions, newCitationId);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
