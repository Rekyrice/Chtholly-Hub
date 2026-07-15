package com.chtholly.agent.trace.dto;

import com.chtholly.agent.trace.ExecutionTraceRow;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TraceDetailDto(
        String correlationId,
        Long userId,
        String sessionId,
        String status,
        Integer durationMs,
        Integer stepsCount,
        String errorMessage,
        JsonNode toolCalls,
        JsonNode tracePayload,
        List<TraceStepDto> steps,
        List<TraceEventDto> unassignedEvents
) {
    public static TraceDetailDto from(ExecutionTraceRow row, JsonNode toolCalls, JsonNode tracePayload) {
        TraceHierarchy hierarchy = buildHierarchy(toolCalls, tracePayload);
        return new TraceDetailDto(
                row.getCorrelationId(),
                row.getUserId(),
                row.getSessionId(),
                row.getStatus(),
                row.getDurationMs(),
                row.getStepsCount(),
                row.getErrorMessage(),
                toolCalls,
                tracePayload,
                hierarchy.steps(),
                hierarchy.unassignedEvents()
        );
    }

    private static TraceHierarchy buildHierarchy(JsonNode toolCalls, JsonNode tracePayload) {
        Map<Integer, StepBuilder> stepBuilders = new LinkedHashMap<>();
        JsonNode rawSteps = tracePayload.path("steps");
        if (rawSteps.isArray()) {
            rawSteps.forEach(node -> {
                Integer stepIndex = nullableInt(node, "stepIndex", "step_index");
                if (stepIndex != null) {
                    stepBuilders.put(stepIndex, new StepBuilder(
                            stepIndex,
                            nullableText(node, "action"),
                            nullableLong(node, "llmMs", "llm_ms"),
                            nullableLong(node, "toolMs", "tool_ms")));
                }
            });
        }

        List<TraceEventDto> events = new ArrayList<>();
        JsonNode llmCalls = tracePayload.path("llmCalls");
        if (llmCalls.isArray()) {
            llmCalls.forEach(node -> events.add(toLlmEvent(node)));
        }
        JsonNode payloadToolCalls = tracePayload.path("toolCalls");
        JsonNode effectiveToolCalls = payloadToolCalls.isArray() ? payloadToolCalls : toolCalls;
        if (effectiveToolCalls != null && effectiveToolCalls.isArray()) {
            effectiveToolCalls.forEach(node -> events.add(toToolEvent(node)));
        }

        List<TraceEventDto> unassigned = new ArrayList<>();
        for (TraceEventDto event : events) {
            if (event.stepIndex() == null) {
                unassigned.add(event);
                continue;
            }
            stepBuilders.computeIfAbsent(
                            event.stepIndex(),
                            index -> new StepBuilder(index, null, null, null))
                    .events.add(event);
        }

        Comparator<TraceEventDto> bySequence = Comparator.comparing(
                TraceEventDto::sequence,
                Comparator.nullsLast(Integer::compareTo));
        unassigned.sort(bySequence);
        List<TraceStepDto> steps = stepBuilders.values().stream()
                .sorted(Comparator.comparing(builder -> builder.stepIndex))
                .map(builder -> builder.toDto(bySequence))
                .toList();
        return new TraceHierarchy(steps, List.copyOf(unassigned));
    }

    private static TraceEventDto toLlmEvent(JsonNode node) {
        return new TraceEventDto(
                nullableInt(node, "sequence"),
                nullableInt(node, "step_index", "stepIndex"),
                "llm",
                "model",
                nullableLong(node, "duration_ms", "durationMs"),
                null,
                null,
                null,
                nullableInt(node, "input_chars", "inputChars"),
                nullableInt(node, "output_chars", "outputChars"),
                nullableLong(node, "first_token_ms", "firstTokenMs"));
    }

    private static TraceEventDto toToolEvent(JsonNode node) {
        return new TraceEventDto(
                nullableInt(node, "sequence"),
                nullableInt(node, "step_index", "stepIndex"),
                "tool",
                nullableText(node, "tool"),
                nullableLong(node, "duration_ms", "durationMs"),
                node.has("success") ? node.path("success").asBoolean() : null,
                nullableText(node, "input_summary", "inputSummary"),
                nullableText(node, "observation_summary", "observationSummary"),
                null,
                null,
                null);
    }

    private static Integer nullableInt(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isIntegralNumber()) {
                return value.intValue();
            }
        }
        return null;
    }

    private static Long nullableLong(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isNumber()) {
                return value.longValue();
            }
        }
        return null;
    }

    private static String nullableText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isTextual()) {
                return value.textValue();
            }
        }
        return null;
    }

    private record TraceHierarchy(List<TraceStepDto> steps, List<TraceEventDto> unassignedEvents) {
    }

    private static final class StepBuilder {
        private final Integer stepIndex;
        private final String action;
        private final Long llmDurationMs;
        private final Long toolDurationMs;
        private final List<TraceEventDto> events = new ArrayList<>();

        private StepBuilder(Integer stepIndex, String action, Long llmDurationMs, Long toolDurationMs) {
            this.stepIndex = stepIndex;
            this.action = action;
            this.llmDurationMs = llmDurationMs;
            this.toolDurationMs = toolDurationMs;
        }

        private TraceStepDto toDto(Comparator<TraceEventDto> comparator) {
            events.sort(comparator);
            return new TraceStepDto(
                    stepIndex,
                    action,
                    llmDurationMs,
                    toolDurationMs,
                    List.copyOf(events));
        }
    }
}
