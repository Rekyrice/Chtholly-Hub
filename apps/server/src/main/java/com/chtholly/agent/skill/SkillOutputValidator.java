package com.chtholly.agent.skill;

import com.chtholly.agent.evidence.EvidenceSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic output validation shared by every preinstalled Skill. */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class SkillOutputValidator {

    private static final String INVALID_OUTPUT = "输出未通过 Skill 合同校验。";
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,3}\\s+\\S+");
    private static final Pattern CITATION = Pattern.compile("\\[E\\d+]");
    private static final Pattern CITATION_ID = Pattern.compile("\\[(E\\d+)]");
    private static final Pattern SPOILER_LIMIT = Pattern.compile(
            "(?:只(?:写|讲|看到)?到|截至|截止|不超过)\\s*第?\\s*(\\d+)\\s*(?:集|话|章)");
    private static final Pattern EPISODE_REFERENCE = Pattern.compile("第?\\s*(\\d+)\\s*(?:集|话|章)");
    private static final Set<String> SUPPORTED_VALIDATORS = Set.of(
            "citation", "length", "outline-structure", "fact-status",
            "spoiler-scope", "source-dedup", "draft-content");

    public Set<String> supportedValidatorIds() {
        return SUPPORTED_VALIDATORS;
    }

    public SkillValidationResult validate(
            SkillDefinition definition,
            String output,
            EvidenceSet evidenceSet) {
        return validate(definition, output, evidenceSet, "");
    }

    public SkillValidationResult validate(
            SkillDefinition definition,
            String output,
            EvidenceSet evidenceSet,
            String userConstraints) {
        String normalized = output == null ? "" : output.strip();
        EvidenceSet evidence = evidenceSet == null ? EvidenceSet.empty() : evidenceSet;
        if (normalized.isBlank()) {
            return invalid(Status.SCHEMA_INVALID, "empty_output");
        }
        if (definition.requiresEvidence() && evidence.isEmpty()) {
            return new SkillValidationResult(
                    Status.INSUFFICIENT_EVIDENCE,
                    EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER,
                    List.of("evidence_required"));
        }
        if (definition.validators().contains("citation")) {
            String validated = evidence.validateFinalAnswer(normalized);
            if (EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER.equals(validated)
                    && !EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER.equals(normalized)) {
                return new SkillValidationResult(
                        Status.CITATION_INVALID,
                        EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER,
                        List.of("citation_not_in_snapshot"));
            }
        }
        if (definition.validators().contains("length")) {
            int maxChars = number(definition.outputSchema().get("maxChars"), 8192);
            if (normalized.length() > maxChars) {
                return invalid(Status.CONSTRAINT_INVALID, "max_chars_exceeded");
            }
        }
        if (definition.validators().contains("outline-structure")) {
            int minSections = number(definition.outputSchema().get("minSections"), 2);
            if (HEADING.matcher(normalized).results().count() < minSections) {
                return invalid(Status.SCHEMA_INVALID, "outline_sections_missing");
            }
        }
        if (definition.validators().contains("fact-status")) {
            SkillValidationResult result = validateFactStatuses(normalized);
            if (result != null) {
                return result;
            }
        }
        if (definition.validators().contains("spoiler-scope")) {
            SkillValidationResult result = validateSpoilerScope(normalized, userConstraints);
            if (result != null) {
                return result;
            }
        }
        if (definition.validators().contains("source-dedup")) {
            SkillValidationResult result = validateSourceDedup(normalized, evidence);
            if (result != null) {
                return result;
            }
        }
        return new SkillValidationResult(Status.VALID, normalized, List.of());
    }

    /** Validates a controlled-write candidate without treating the draft as evidence. */
    public SkillValidationResult validateDraftContent(
            SkillDefinition definition,
            String baseContent,
            String candidateContent) {
        String base = baseContent == null ? "" : baseContent.strip();
        String candidate = candidateContent == null ? "" : candidateContent.strip();
        if (candidate.isBlank()) {
            return invalid(Status.SCHEMA_INVALID, "empty_output");
        }
        if (definition.validators().contains("draft-content") && candidate.equals(base)) {
            return invalid(Status.CONSTRAINT_INVALID, "no_content_change");
        }
        if (definition.validators().contains("length")) {
            int maxChars = number(definition.outputSchema().get("maxChars"), 8192);
            if (candidate.length() > maxChars) {
                return invalid(Status.CONSTRAINT_INVALID, "max_chars_exceeded");
            }
        }
        return new SkillValidationResult(Status.VALID, candidate, List.of());
    }

    private SkillValidationResult validateSpoilerScope(String output, String userConstraints) {
        OptionalInt limit = spoilerLimit(userConstraints);
        if (limit.isEmpty()) {
            return null;
        }
        var references = EPISODE_REFERENCE.matcher(output);
        while (references.find()) {
            if (Integer.parseInt(references.group(1)) > limit.getAsInt()) {
                return invalid(Status.CONSTRAINT_INVALID, "spoiler_scope_exceeded");
            }
        }
        return null;
    }

    private OptionalInt spoilerLimit(String userConstraints) {
        var matcher = SPOILER_LIMIT.matcher(userConstraints == null ? "" : userConstraints);
        int limit = Integer.MAX_VALUE;
        boolean found = false;
        while (matcher.find()) {
            limit = Math.min(limit, Integer.parseInt(matcher.group(1)));
            found = true;
        }
        return found ? OptionalInt.of(limit) : OptionalInt.empty();
    }

    private SkillValidationResult validateSourceDedup(String output, EvidenceSet evidenceSet) {
        Set<String> citedIds = new LinkedHashSet<>();
        var matcher = CITATION_ID.matcher(output);
        while (matcher.find()) {
            citedIds.add(matcher.group(1));
        }
        Set<String> documentIds = new LinkedHashSet<>();
        for (var evidence : evidenceSet.items()) {
            if (citedIds.contains(evidence.citationId()) && !documentIds.add(evidence.documentId())) {
                return invalid(Status.CONSTRAINT_INVALID, "duplicate_source_document");
            }
        }
        return null;
    }

    private SkillValidationResult validateFactStatuses(String output) {
        boolean foundClaim = false;
        for (String line : output.split("\\R")) {
            if (!line.startsWith("|") || line.contains("---") || line.contains("判定")) {
                continue;
            }
            String upper = line.toUpperCase();
            boolean recognized = upper.contains("SUPPORTED")
                    || upper.contains("CONFLICTED")
                    || upper.contains("INSUFFICIENT");
            if (!recognized) {
                return invalid(Status.SCHEMA_INVALID, "unknown_fact_status");
            }
            foundClaim = true;
            if (upper.contains("SUPPORTED") && !CITATION.matcher(line).find()) {
                return new SkillValidationResult(
                        Status.CITATION_INVALID,
                        EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER,
                        List.of("supported_claim_without_citation"));
            }
        }
        return foundClaim ? null : invalid(Status.SCHEMA_INVALID, "claim_rows_missing");
    }

    private SkillValidationResult invalid(Status status, String error) {
        return new SkillValidationResult(status, INVALID_OUTPUT, List.of(error));
    }

    private int number(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public enum Status {
        VALID,
        INSUFFICIENT_EVIDENCE,
        SCHEMA_INVALID,
        CITATION_INVALID,
        CONSTRAINT_INVALID
    }

    public record SkillValidationResult(Status status, String output, List<String> errors) {
        public SkillValidationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }
}
