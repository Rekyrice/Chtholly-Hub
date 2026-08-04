package com.chtholly.agent.context;

import com.chtholly.agent.evidence.EvidenceSet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/** Immutable system instructions and evidence frozen for one Agent turn. */
public record AgentContextSnapshot(
        String snapshotId,
        String systemPrompt,
        String finalSystemPrompt,
        EvidenceSet evidenceSet,
        boolean evidenceRequired,
        Map<String, String> retrievalStatuses) {

    public AgentContextSnapshot {
        systemPrompt = systemPrompt == null ? "" : systemPrompt.strip();
        finalSystemPrompt = finalSystemPrompt == null ? systemPrompt : finalSystemPrompt.strip();
        evidenceSet = evidenceSet == null ? EvidenceSet.empty() : evidenceSet;
        retrievalStatuses = retrievalStatuses == null ? Map.of() : Map.copyOf(retrievalStatuses);
        if (snapshotId == null || snapshotId.isBlank()) {
            snapshotId = snapshotId(
                    systemPrompt,
                    finalSystemPrompt,
                    evidenceSet,
                    evidenceRequired,
                    retrievalStatuses);
        }
    }

    public AgentContextSnapshot(String systemPrompt, EvidenceSet evidenceSet, boolean evidenceRequired) {
        this(null, systemPrompt, systemPrompt, evidenceSet, evidenceRequired, Map.of());
    }

    public AgentContextSnapshot(
            String systemPrompt,
            EvidenceSet evidenceSet,
            boolean evidenceRequired,
            Map<String, String> retrievalStatuses) {
        this(null, systemPrompt, systemPrompt, evidenceSet, evidenceRequired, retrievalStatuses);
    }

    public AgentContextSnapshot(
            String systemPrompt,
            String finalSystemPrompt,
            EvidenceSet evidenceSet,
            boolean evidenceRequired) {
        this(null, systemPrompt, finalSystemPrompt, evidenceSet, evidenceRequired, Map.of());
    }

    public AgentContextSnapshot(
            String systemPrompt,
            String finalSystemPrompt,
            EvidenceSet evidenceSet,
            boolean evidenceRequired,
            Map<String, String> retrievalStatuses) {
        this(null, systemPrompt, finalSystemPrompt, evidenceSet, evidenceRequired, retrievalStatuses);
    }

    /** Replaces both loop and final-answer instructions and derives a new snapshot identity. */
    public AgentContextSnapshot withSystemPrompt(String updatedSystemPrompt) {
        return new AgentContextSnapshot(
                updatedSystemPrompt,
                updatedSystemPrompt,
                evidenceSet,
                evidenceRequired,
                retrievalStatuses);
    }

    /** Rebinds loop and final-answer instructions while preserving their protocol boundary. */
    public AgentContextSnapshot withSystemPrompts(
            String updatedSystemPrompt,
            String updatedFinalSystemPrompt) {
        return new AgentContextSnapshot(
                updatedSystemPrompt,
                updatedFinalSystemPrompt,
                evidenceSet,
                evidenceRequired,
                retrievalStatuses);
    }

    /** Rebinds the final evidence snapshot without leaving stale citation content in the prompt. */
    public String renderSystemPrompt(EvidenceSet effectiveEvidenceSet) {
        return renderPrompt(systemPrompt, effectiveEvidenceSet);
    }

    /** Renders the answer-only context without exposing the ReAct tool protocol. */
    public String renderFinalSystemPrompt(EvidenceSet effectiveEvidenceSet) {
        return renderPrompt(finalSystemPrompt, effectiveEvidenceSet);
    }

    private String renderPrompt(String basePrompt, EvidenceSet effectiveEvidenceSet) {
        EvidenceSet effective = effectiveEvidenceSet == null ? EvidenceSet.empty() : effectiveEvidenceSet;
        String instructions = basePrompt;
        String initialEvidence = evidenceSet.renderForPrompt();
        if (!initialEvidence.isBlank()) {
            int evidenceStart = instructions.indexOf(initialEvidence);
            if (evidenceStart >= 0) {
                instructions = (instructions.substring(0, evidenceStart)
                        + instructions.substring(evidenceStart + initialEvidence.length())).strip();
            }
        }
        String renderedEvidence = effective.renderForPrompt();
        if (renderedEvidence.isBlank()) {
            return instructions;
        }
        return instructions.isBlank()
                ? renderedEvidence
                : instructions + "\n\n" + renderedEvidence;
    }

    private static String snapshotId(
            String systemPrompt,
            String finalSystemPrompt,
            EvidenceSet evidenceSet,
            boolean evidenceRequired,
            Map<String, String> retrievalStatuses) {
        String canonical = systemPrompt + "\n--final-system--\n" + finalSystemPrompt
                + "\n--evidence--\n" + evidenceSet.contentHash()
                + "\n--required--\n" + evidenceRequired
                + "\n--retrieval--\n" + new TreeMap<>(retrievalStatuses);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
