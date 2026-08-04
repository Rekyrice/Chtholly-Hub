package com.chtholly.agent.response;

import com.chtholly.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Validates and normalizes the model output protocol used for client-visible final answers.
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentFinalAnswerProtocol {

    private final ObjectMapper objectMapper;
    private final AgentProperties properties;

    /** Creates the final-answer protocol validator. */
    public AgentFinalAnswerProtocol(ObjectMapper objectMapper, AgentProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** Returns whether the candidate accidentally exposes an internal action envelope. */
    public boolean isActionEnvelope(String candidate) {
        String normalized = unwrapSingleJsonFence(candidate);
        if (normalized.isBlank()) {
            return false;
        }
        try {
            var root = objectMapper.readTree(normalized);
            return root != null && root.isObject() && root.path("action").isTextual();
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Returns whether a repaired answer changed only citation markers and whitespace. */
    public boolean sameContentExceptCitations(String original, String repaired) {
        if (repaired == null || repaired.isBlank()) {
            return false;
        }
        String originalContent = original == null ? "" : original.replaceAll("\\s+", "");
        String repairedContent = repaired
                .replaceAll("\\[E\\d+]", "")
                .replaceAll("\\s+", "");
        return originalContent.equals(repairedContent);
    }

    /** Truncates a model answer at the configured visible response limit. */
    public String truncate(String answer) {
        if (answer == null || answer.isEmpty()) {
            return "";
        }
        int max = Math.max(1, properties.getMaxResponseChars());
        return answer.length() <= max ? answer : answer.substring(0, max);
    }

    private String unwrapSingleJsonFence(String candidate) {
        String normalized = candidate == null ? "" : candidate.strip();
        if (!normalized.startsWith("```") || !normalized.endsWith("```")) {
            return normalized;
        }
        int firstLineEnd = normalized.indexOf('\n');
        int closingFence = normalized.lastIndexOf("```");
        if (firstLineEnd < 0 || closingFence <= firstLineEnd) {
            return normalized;
        }
        return normalized.substring(firstLineEnd + 1, closingFence).strip();
    }
}
