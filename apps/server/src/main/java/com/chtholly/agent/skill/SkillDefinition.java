package com.chtholly.agent.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, versioned contract for one preinstalled Agent capability. */
public record SkillDefinition(
        String id,
        String version,
        boolean enabled,
        String description,
        List<String> supportedIntents,
        List<String> requiredContext,
        List<String> allowedTools,
        String instructionTemplate,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        List<String> validators,
        String riskLevel,
        String approvalPolicy,
        long timeoutBudgetMs,
        int maxSteps,
        String evaluationDatasetVersion) {

    public SkillDefinition {
        id = required(id, "id");
        version = required(version, "version");
        description = required(description, "description");
        instructionTemplate = required(instructionTemplate, "instructionTemplate");
        riskLevel = required(riskLevel, "riskLevel");
        approvalPolicy = required(approvalPolicy, "approvalPolicy");
        evaluationDatasetVersion = required(evaluationDatasetVersion, "evaluationDatasetVersion");
        supportedIntents = copy(supportedIntents);
        requiredContext = copy(requiredContext);
        allowedTools = copy(allowedTools);
        validators = copy(validators);
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(inputSchema));
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(outputSchema));
        if (timeoutBudgetMs <= 0 || maxSteps <= 0) {
            throw new IllegalArgumentException("Skill timeoutBudgetMs and maxSteps must be positive");
        }
    }

    public String key() {
        return id + '@' + version;
    }

    public String outputType() {
        Object value = outputSchema.get("type");
        return value == null ? "UNKNOWN" : String.valueOf(value);
    }

    public boolean requiresEvidence() {
        return Boolean.parseBoolean(String.valueOf(outputSchema.getOrDefault("requiresEvidence", false)));
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Skill " + field + " must not be blank");
        }
        return value;
    }
}
