package com.chtholly.agent.context;

import com.chtholly.agent.evidence.EvidenceSet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Immutable system instructions and evidence frozen for one Agent turn. */
public record AgentContextSnapshot(
        String snapshotId,
        String systemPrompt,
        EvidenceSet evidenceSet,
        boolean evidenceRequired) {

    public AgentContextSnapshot {
        systemPrompt = systemPrompt == null ? "" : systemPrompt.strip();
        evidenceSet = evidenceSet == null ? EvidenceSet.empty() : evidenceSet;
        if (snapshotId == null || snapshotId.isBlank()) {
            snapshotId = snapshotId(systemPrompt, evidenceSet, evidenceRequired);
        }
    }

    public AgentContextSnapshot(String systemPrompt, EvidenceSet evidenceSet, boolean evidenceRequired) {
        this(null, systemPrompt, evidenceSet, evidenceRequired);
    }

    /** Rebinds immutable Skill instructions and derives a new snapshot identity. */
    public AgentContextSnapshot withSystemPrompt(String updatedSystemPrompt) {
        return new AgentContextSnapshot(updatedSystemPrompt, evidenceSet, evidenceRequired);
    }

    private static String snapshotId(
            String systemPrompt,
            EvidenceSet evidenceSet,
            boolean evidenceRequired) {
        String canonical = systemPrompt + "\n--evidence--\n" + evidenceSet.contentHash()
                + "\n--required--\n" + evidenceRequired;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
