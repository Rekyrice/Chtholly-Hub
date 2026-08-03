package com.chtholly.agent.trace.dto;

import com.chtholly.agent.observability.AgentTraceSanitizer;
import com.chtholly.agent.trace.ExecutionTraceRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Converts persisted trace JSON into a bounded administrator detail contract. */
final class TraceDetailProjector {

    private static final Logger log = LoggerFactory.getLogger(TraceDetailProjector.class);

    private static final String TRACE_V4 = "agent-trace-v4";
    private static final String TRACE_V3 = "agent-trace-v3";
    private static final int MAX_EVENTS = 256;
    private static final int MAX_ITEMS = AgentTraceSanitizer.MAX_COLLECTION_ITEMS;
    private static final int MAX_CONTENT_FIELD_CHARS = 131_072;
    private static final int MAX_ATTRIBUTE_ENTRIES = 64;
    private static final int MAX_ATTRIBUTE_DEPTH = 6;
    private static final int MAX_ATTRIBUTE_STRING_CHARS = 16_384;
    private static final Pattern STABLE_CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern LEGACY_SUMMARY = Pattern.compile(
            "sha256=[0-9a-fA-F]{64};chars=(?:0|[1-9][0-9]*)");
    private static final Set<String> V4_PHASES = Set.of(
            "accepted", "skill", "plan", "retrieval", "llm", "tool",
            "validation", "memory", "delivery");
    private static final Set<String> V4_TYPES = Set.of("lifecycle", "llm", "tool");
    private static final Set<String> INTERNAL_INPUT_KEYS = Set.of(
            "_userquestion", "_conversationhistory");

    private TraceDetailProjector() {
    }

    static TraceDetailDto project(ExecutionTraceRow row, ObjectMapper objectMapper) {
        ExecutionTraceRow safeRow = Objects.requireNonNull(row, "row");
        ObjectMapper safeMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        PayloadProjection payload = projectPayload(safeRow, safeMapper);
        return new TraceDetailDto(
                safeRow.getCorrelationId(),
                safeRow.getUserId(),
                safeRow.getSessionId(),
                safeRow.getStatus(),
                safeRow.getDurationMs(),
                safeRow.getStepsCount(),
                safeRow.getErrorMessage() == null
                        ? null
                        : AgentTraceSanitizer.safeMessage(safeRow.getErrorMessage()),
                payload.compatibility(),
                payload.timingAccuracy(),
                payload.phases(),
                payload.metadata());
    }

    private static PayloadProjection projectPayload(ExecutionTraceRow row, ObjectMapper objectMapper) {
        JsonParseResult payload = parseObject(
                row.getTracePayload(), objectMapper, row.getCorrelationId(), "trace_payload");
        if (!payload.valid()) {
            return PayloadProjection.malformed();
        }
        JsonNode root = payload.value();
        JsonNode components = root.get("components");
        if (components == null || components.isNull()) {
            return PayloadProjection.unsupported();
        }
        if (!components.isObject()) {
            return PayloadProjection.malformed();
        }
        JsonNode schema = components.get("traceSchema");
        if (schema == null || schema.isNull()) {
            return PayloadProjection.unsupported();
        }
        if (!schema.isTextual()) {
            return PayloadProjection.malformed();
        }
        return switch (schema.textValue()) {
            case TRACE_V4 -> projectNativeV4(row, root);
            case TRACE_V3 -> projectLegacyV3(row, root, objectMapper);
            default -> PayloadProjection.unsupported();
        };
    }

    private static PayloadProjection projectNativeV4(ExecutionTraceRow row, JsonNode root) {
        JsonNode rawEvents = root.get("events");
        if (rawEvents == null || !rawEvents.isArray() || rawEvents.size() > MAX_EVENTS) {
            return PayloadProjection.malformed();
        }

        List<TraceEventDto> events = new ArrayList<>();
        Set<Integer> sequences = new HashSet<>();
        for (JsonNode rawEvent : rawEvents) {
            TraceEventDto event = nativeEvent(row.getCorrelationId(), rawEvent);
            if (event == null || !sequences.add(event.sequence())) {
                return PayloadProjection.malformed();
            }
            events.add(event);
        }
        events.sort(Comparator.comparingInt(TraceEventDto::sequence));
        return new PayloadProjection(
                TraceDetailDto.Compatibility.NATIVE_V4,
                TraceDetailDto.TimingAccuracy.EXACT,
                contiguousPhases(events),
                metadata(root, null, null));
    }

    private static TraceEventDto nativeEvent(String correlationId, JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Integer sequence = positiveInteger(node, "sequence");
        Long startedOffsetMs = requiredNonNegativeLong(node, "started_offset_ms", "startedOffsetMs");
        Long durationMs = requiredNonNegativeLong(node, "duration_ms", "durationMs");
        String phase = stableCode(node, "phase");
        String type = stableCode(node, "type");
        String name = safeText(node, "name");
        String status = stableCode(node, "status");
        if (sequence == null
                || startedOffsetMs == null
                || durationMs == null
                || !V4_PHASES.contains(phase)
                || !V4_TYPES.contains(type)
                || name == null
                || status == null
                || ("llm".equals(type) && !"llm".equals(phase))
                || ("tool".equals(type) && !"tool".equals(phase))
                || ("lifecycle".equals(type) && ("llm".equals(phase) || "tool".equals(phase)))) {
            return null;
        }

        JsonNode detailsNode = node.get("details");
        if (detailsNode != null && !detailsNode.isNull() && !detailsNode.isObject()) {
            return null;
        }
        JsonNode safeDetails = detailsNode == null || detailsNode.isNull()
                ? null
                : detailsNode;
        String errorCode = stableCode(node, "error_code", "errorCode");
        if (errorCode == null && safeDetails != null) {
            errorCode = stableCode(safeDetails, "error_code", "errorCode");
        }

        TraceEventDto.Details details = switch (type) {
            case "llm" -> llmDetails(safeDetails);
            case "tool" -> toolDetails(safeDetails, null, null);
            default -> lifecycleDetails(safeDetails);
        };
        return new TraceEventDto(
                eventId(correlationId, String.valueOf(sequence)),
                sequence,
                nonNegativeInteger(node, "step_index", "stepIndex"),
                phase,
                type,
                name,
                status,
                startedOffsetMs,
                durationMs,
                positiveInteger(node, "attempt"),
                nonNegativeLong(node, "budget_before_ms", "budgetBeforeMs"),
                nonNegativeLong(node, "budget_after_ms", "budgetAfterMs"),
                errorCode,
                details);
    }

    private static PayloadProjection projectLegacyV3(
            ExecutionTraceRow row,
            JsonNode root,
            ObjectMapper objectMapper) {
        JsonNode llmCalls = root.get("llmCalls");
        if (llmCalls == null || llmCalls.isNull()) {
            llmCalls = objectMapper.createArrayNode();
        }
        if (!llmCalls.isArray()) {
            return PayloadProjection.malformed();
        }

        JsonNode toolCalls = root.get("toolCalls");
        if (toolCalls == null || toolCalls.isNull()) {
            JsonArrayParseResult parsed = parseArray(
                    row.getToolCalls(), objectMapper, row.getCorrelationId(), "tool_calls");
            if (!parsed.valid()) {
                return PayloadProjection.malformed();
            }
            toolCalls = parsed.value();
        }
        if (!toolCalls.isArray() || llmCalls.size() + toolCalls.size() > MAX_EVENTS) {
            return PayloadProjection.malformed();
        }

        List<TraceEventDto> llmEvents = new ArrayList<>();
        for (int index = 0; index < llmCalls.size(); index++) {
            TraceEventDto event = legacyLlmEvent(row.getCorrelationId(), llmCalls.get(index), index);
            if (event == null) {
                return PayloadProjection.malformed();
            }
            llmEvents.add(event);
        }
        List<TraceEventDto> toolEvents = new ArrayList<>();
        for (int index = 0; index < toolCalls.size(); index++) {
            TraceEventDto event = legacyToolEvent(row.getCorrelationId(), toolCalls.get(index), index);
            if (event == null) {
                return PayloadProjection.malformed();
            }
            toolEvents.add(event);
        }

        List<TraceEventDto> events = new ArrayList<>(llmEvents.size() + toolEvents.size());
        events.addAll(llmEvents);
        events.addAll(toolEvents);
        events.sort(Comparator.comparing(
                TraceEventDto::sequence,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return new PayloadProjection(
                TraceDetailDto.Compatibility.LEGACY_V3,
                TraceDetailDto.TimingAccuracy.DURATION_ONLY,
                contiguousPhases(events),
                metadata(root, llmCalls.size(), toolCalls.size()));
    }

    private static TraceEventDto legacyLlmEvent(String correlationId, JsonNode node, int index) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Long durationMs = requiredNonNegativeLong(node, "duration_ms", "durationMs");
        if (durationMs == null) {
            return null;
        }
        return new TraceEventDto(
                eventId(correlationId, "legacy:llm:" + (index + 1)),
                nonNegativeInteger(node, "sequence"),
                nonNegativeInteger(node, "step_index", "stepIndex"),
                "llm",
                "llm",
                "llm_call",
                defaultCode(stableCode(node, "status"), "UNKNOWN"),
                null,
                durationMs,
                positiveInteger(node, "attempt"),
                nonNegativeLong(node, "budget_before_ms", "budgetBeforeMs"),
                nonNegativeLong(node, "budget_after_ms", "budgetAfterMs"),
                stableCode(node, "error_code", "errorCode"),
                llmDetails(node));
    }

    private static TraceEventDto legacyToolEvent(String correlationId, JsonNode node, int index) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Long durationMs = requiredNonNegativeLong(node, "duration_ms", "durationMs");
        String tool = safeText(node, "tool", "name");
        if (durationMs == null || tool == null) {
            return null;
        }
        String status = stableCode(node, "status");
        if (status == null) {
            Boolean success = booleanValue(node, "success");
            status = success == null ? "UNKNOWN" : success ? "SUCCESS" : "ERROR";
        }
        String inputSummary = strictSummary(node, "input_summary", "inputSummary", "input");
        String observationSummary = strictSummary(
                node, "observation_summary", "observationSummary", "observation");
        return new TraceEventDto(
                eventId(correlationId, "legacy:tool:" + (index + 1)),
                nonNegativeInteger(node, "sequence"),
                nonNegativeInteger(node, "step_index", "stepIndex"),
                "tool",
                "tool",
                tool,
                status,
                null,
                durationMs,
                positiveInteger(node, "attempt"),
                nonNegativeLong(node, "budget_before_ms", "budgetBeforeMs"),
                nonNegativeLong(node, "budget_after_ms", "budgetAfterMs"),
                stableCode(node, "error_code", "errorCode"),
                toolDetails(node, inputSummary, observationSummary));
    }

    private static List<TracePhaseDto> contiguousPhases(List<TraceEventDto> events) {
        List<TracePhaseDto> phases = new ArrayList<>();
        String activePhase = null;
        List<TraceEventDto> activeEvents = new ArrayList<>();
        for (TraceEventDto event : events) {
            if (!event.phase().equals(activePhase)) {
                if (!activeEvents.isEmpty()) {
                    phases.add(new TracePhaseDto(activePhase, List.copyOf(activeEvents)));
                }
                activePhase = event.phase();
                activeEvents = new ArrayList<>();
            }
            activeEvents.add(event);
        }
        if (!activeEvents.isEmpty()) {
            phases.add(new TracePhaseDto(activePhase, List.copyOf(activeEvents)));
        }
        return List.copyOf(phases);
    }

    private static TraceEventDto.LlmDetails llmDetails(JsonNode details) {
        if (details == null || !details.isObject()) {
            return null;
        }
        TraceEventDto.LlmDetails projected = new TraceEventDto.LlmDetails(
                safeText(details, "purpose"),
                safeText(details, "model"),
                nonNegativeInteger(details, "input_chars", "inputChars"),
                nonNegativeInteger(details, "output_chars", "outputChars"),
                nonNegativeLong(details, "first_token_ms", "firstTokenMs"),
                content(details.get("systemPrompt")),
                content(details.get("userPrompt")),
                content(details.get("rawOutput")),
                contentText(details.get("failureClass")),
                content(details.get("failureMessage")));
        return allNull(
                projected.purpose(),
                projected.model(),
                projected.inputChars(),
                projected.outputChars(),
                projected.firstTokenMs(),
                projected.systemPrompt(),
                projected.userPrompt(),
                projected.rawOutput(),
                projected.failureClass(),
                projected.failureMessage())
                ? null
                : projected;
    }

    private static TraceEventDto.ToolDetails toolDetails(
            JsonNode details,
            String inputSummary,
            String observationSummary) {
        if (details == null || !details.isObject()) {
            return inputSummary == null && observationSummary == null
                    ? null
                    : new TraceEventDto.ToolDetails(
                            null, null, null, null, null, null, null, null,
                            null, null, inputSummary, observationSummary, null, null, Map.of());
        }
        Map<String, Object> sanitizedInput = sanitizedInput(details.get("sanitizedInput"));
        List<String> selectedIds = defaultList(safeStringList(details.get("selectedIds")));
        String operation = safeText(details, "operation");
        String provider = safeText(details, "provider");
        String sourcePolicy = safeText(details, "sourcePolicy", "source_policy");
        Map<String, Object> effectiveInput = sanitizedInput == null || sanitizedInput.isEmpty()
                ? null
                : sanitizedInput;
        String outputPreview = safePreview(details, "outputPreview", "output_preview");
        String outputSha256 = sha256(details, "outputSha256", "output_sha256");
        Integer outputChars = nonNegativeInteger(details, "outputChars", "output_chars");
        Boolean outputTruncated = booleanValue(details, "outputTruncated", "output_truncated");
        Integer resultCount = nonNegativeInteger(details, "resultCount", "result_count");
        TraceContentDto rawInput = firstContent(details, "rawInput", "input");
        TraceContentDto rawObservation = firstContent(details, "rawObservation", "observation");
        Map<String, Object> attributes = adminAttributes(details.get("attributes"));
        if (allNull(
                operation,
                provider,
                sourcePolicy,
                effectiveInput,
                outputPreview,
                outputSha256,
                outputChars,
                outputTruncated,
                resultCount,
                inputSummary,
                observationSummary,
                rawInput,
                rawObservation,
                attributes)
                && selectedIds.isEmpty()) {
            return null;
        }
        return new TraceEventDto.ToolDetails(
                operation,
                provider,
                sourcePolicy,
                effectiveInput,
                outputPreview,
                outputSha256,
                outputChars,
                outputTruncated,
                resultCount,
                selectedIds,
                inputSummary,
                observationSummary,
                rawInput,
                rawObservation,
                attributes);
    }

    private static TraceEventDto.LifecycleDetails lifecycleDetails(JsonNode details) {
        if (details == null || !details.isObject()) {
            return null;
        }
        TraceEventDto.LifecycleDetails projected = new TraceEventDto.LifecycleDetails(
                safeText(details, "model"),
                safeText(details, "run_mode", "runMode"),
                safeText(details, "skill_id", "skillId"),
                safeText(details, "skill_version", "skillVersion"),
                nonNegativeInteger(details, "source_count", "sourceCount"),
                nonNegativeInteger(details, "evidence_count", "evidenceCount"),
                stableCode(details, "reason"),
                nonNegativeInteger(details, "tool_count", "toolCount"),
                nonNegativeLong(details, "budget_ms", "budgetMs"),
                stableCode(details, "stage"),
                stableCode(details, "terminal_type", "terminalType"),
                stableCode(details, "delivery_code", "deliveryCode"),
                nonNegativeLong(details, "model_first_token_ms", "modelFirstTokenMs"),
                nonNegativeLong(details, "safe_answer_ready_ms", "safeAnswerReadyMs"),
                nonNegativeLong(details, "first_client_delta_ms", "firstClientDeltaMs"),
                nonNegativeInteger(details, "answer_chars", "answerChars"),
                firstContent(details, "finalAnswer", "answer"));
        return allNull(
                projected.model(),
                projected.runMode(),
                projected.skillId(),
                projected.skillVersion(),
                projected.sourceCount(),
                projected.evidenceCount(),
                projected.reason(),
                projected.toolCount(),
                projected.budgetMs(),
                projected.stage(),
                projected.terminalType(),
                projected.deliveryCode(),
                projected.modelFirstTokenMs(),
                projected.safeAnswerReadyMs(),
                projected.firstClientDeltaMs(),
                projected.answerChars(),
                projected.finalAnswer())
                ? null
                : projected;
    }

    private static TraceMetadataDto metadata(
            JsonNode root,
            Integer fallbackLlmCallCount,
            Integer fallbackToolCallCount) {
        JsonNode rawToolCalls = root.get("toolCalls");
        Integer toolCallCount = fallbackToolCallCount;
        if (rawToolCalls != null && rawToolCalls.isArray()) {
            toolCallCount = rawToolCalls.size();
        }
        JsonNode rawLlmCalls = root.get("llmCalls");
        Integer llmCallCount = nonNegativeInteger(root, "llmCallCount");
        if (llmCallCount == null && rawLlmCalls != null && rawLlmCalls.isArray()) {
            llmCallCount = rawLlmCalls.size();
        }
        if (llmCallCount == null) {
            llmCallCount = fallbackLlmCallCount;
        }
        return new TraceMetadataDto(
                stableCode(root, "runMode", "run_mode"),
                stableCode(root, "failureType", "failure_type"),
                stableCode(root, "outcomeReason", "outcome_reason"),
                llmCallCount,
                toolCallCount,
                components(root.get("components")),
                skill(root.get("skill")),
                retrieval(root.get("retrieval")),
                turn(root.get("turn")),
                memory(root.get("memory")),
                toolPlan(root.get("toolPlan")),
                steps(root.get("steps")),
                answerTiming(root.get("answerTiming")),
                capture(root.get("capture")),
                completeness(root.get("privacy")),
                input(root.get("input"), root.get("events")));
    }

    private static TraceMetadataDto.Components components(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new TraceMetadataDto.Components(
                safeText(node, "prompt"),
                safeText(node, "skillSelector"),
                safeText(node, "model"),
                safeText(node, "retrieval"),
                safeText(node, "citationValidator"),
                safeText(node, "tools"),
                safeText(node, "traceSchema"));
    }

    private static TraceMetadataDto.Skill skill(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        TraceMetadataDto.Skill projected = new TraceMetadataDto.Skill(
                stableCode(node, "selectionStatus"),
                safeText(node, "id"),
                safeText(node, "version"),
                stableCode(node, "validationStatus"));
        return allNull(
                projected.selectionStatus(),
                projected.id(),
                projected.version(),
                projected.validationStatus())
                ? null
                : projected;
    }

    private static TraceMetadataDto.Retrieval retrieval(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode statusesNode = node.get("statuses");
        TraceMetadataDto.RetrievalStatuses statuses = statusesNode != null && statusesNode.isObject()
                ? new TraceMetadataDto.RetrievalStatuses(
                        stableCode(statusesNode, "semantic"),
                        stableCode(statusesNode, "keyword"),
                        stableCode(statusesNode, "entity"),
                        stableCode(statusesNode, "current_post", "currentPost"))
                : new TraceMetadataDto.RetrievalStatuses(null, null, null, null);
        List<TraceMetadataDto.Evidence> evidence = evidence(node.get("evidence"));
        return new TraceMetadataDto.Retrieval(
                safeText(node, "strategy"),
                statuses,
                nonNegativeInteger(node, "evidenceCount"),
                safeText(node, "evidenceSnapshotHash"),
                booleanValue(node, "degraded"),
                stableCode(node, "citationValidationStatus"),
                evidence);
    }

    private static List<TraceMetadataDto.Evidence> evidence(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<TraceMetadataDto.Evidence> evidence = new ArrayList<>();
        int limit = Math.min(node.size(), MAX_ITEMS);
        for (int index = 0; index < limit; index++) {
            JsonNode item = node.get(index);
            if (item == null || !item.isObject()) {
                continue;
            }
            TraceMetadataDto.Evidence projected = new TraceMetadataDto.Evidence(
                    safeText(item, "citationId"),
                    safeText(item, "documentId"),
                    stableCode(item, "source"),
                    safeText(item, "sourceVersion"),
                    safeText(item, "sourceHash"));
            if (!allNull(
                    projected.citationId(),
                    projected.documentId(),
                    projected.source(),
                    projected.sourceVersion(),
                    projected.sourceHash())) {
                evidence.add(projected);
            }
        }
        return List.copyOf(evidence);
    }

    private static TraceMetadataDto.Turn turn(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        TraceMetadataDto.Turn projected = new TraceMetadataDto.Turn(
                safeText(node, "requestId"),
                safeText(node, "turnId"),
                safeText(node, "chatSessionId"),
                safeText(node, "connectionId"),
                nonNegativeLong(node, "budgetMs"),
                positiveInteger(node, "maxSteps"),
                stableCode(node, "timeoutStage"),
                booleanValue(node, "cancelled"),
                stableCode(node, "clientDeliveryStatus"),
                stableCode(node, "clientTerminalType"),
                stableCode(node, "clientDeliveryCode"));
        return allNull(
                projected.requestId(),
                projected.turnId(),
                projected.chatSessionId(),
                projected.connectionId(),
                projected.budgetMs(),
                projected.maxSteps(),
                projected.timeoutStage(),
                projected.cancelled(),
                projected.clientDeliveryStatus(),
                projected.clientTerminalType(),
                projected.clientDeliveryCode())
                ? null
                : projected;
    }

    private static TraceMetadataDto.Memory memory(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        TraceMetadataDto.Memory projected = new TraceMetadataDto.Memory(
                stableCode(node, "writeStatus"),
                stableCode(node, "failureCode"));
        return allNull(projected.writeStatus(), projected.failureCode()) ? null : projected;
    }

    private static TraceMetadataDto.ToolPlan toolPlan(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        List<String> tools = defaultList(safeStringList(node.get("effectiveTools")));
        return new TraceMetadataDto.ToolPlan(
                stableCode(node, "reason"),
                tools);
    }

    private static List<TraceMetadataDto.Step> steps(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<TraceMetadataDto.Step> projected = new ArrayList<>();
        int limit = Math.min(node.size(), MAX_EVENTS);
        for (int index = 0; index < limit; index++) {
            JsonNode item = node.get(index);
            if (item == null || !item.isObject()) {
                continue;
            }
            Integer stepIndex = nonNegativeInteger(item, "stepIndex", "step_index");
            String action = stableCode(item, "action");
            Long llmMs = nonNegativeLong(item, "llmMs", "llm_ms");
            Long toolMs = nonNegativeLong(item, "toolMs", "tool_ms");
            if (stepIndex == null || action == null || llmMs == null || toolMs == null) {
                continue;
            }
            projected.add(new TraceMetadataDto.Step(stepIndex, action, llmMs, toolMs));
        }
        return List.copyOf(projected);
    }

    private static TraceMetadataDto.AnswerTiming answerTiming(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        TraceMetadataDto.AnswerTiming projected = new TraceMetadataDto.AnswerTiming(
                nonNegativeLong(node, "modelFirstTokenMs"),
                nonNegativeLong(node, "safeAnswerReadyMs"),
                nonNegativeLong(node, "firstClientDeltaMs"));
        return allNull(
                projected.modelFirstTokenMs(),
                projected.safeAnswerReadyMs(),
                projected.firstClientDeltaMs())
                ? null
                : projected;
    }

    private static TraceMetadataDto.Capture capture(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        TraceMetadataDto.Capture projected = new TraceMetadataDto.Capture(
                stableCode(node, "level", "captureLevel"),
                stableCode(node, "policyVersion"),
                nonNegativeInteger(node, "maxContentFieldChars", "maxPerFieldChars"),
                nonNegativeInteger(node, "maxTotalContentChars", "maxCapturedChars"),
                nonNegativeInteger(node, "capturedContentChars", "capturedChars"),
                nonNegativeInteger(node, "truncatedContentFields", "truncatedFields"),
                nonNegativeInteger(node, "credentialRedactions", "redactions"));
        return allNull(
                projected.level(),
                projected.policyVersion(),
                projected.maxContentFieldChars(),
                projected.maxTotalContentChars(),
                projected.capturedContentChars(),
                projected.truncatedContentFields(),
                projected.credentialRedactions())
                ? null
                : projected;
    }

    private static TraceMetadataDto.Completeness completeness(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Integer eventLimit = nonNegativeInteger(node, "eventLimit", "event_limit");
        Integer droppedEvents = nonNegativeInteger(node, "droppedEvents", "dropped_events");
        Integer truncatedToolOutputs = nonNegativeInteger(
                node, "truncatedToolOutputs", "truncated_tool_outputs");
        if (eventLimit == null && droppedEvents == null && truncatedToolOutputs == null) {
            return null;
        }
        return new TraceMetadataDto.Completeness(
                eventLimit,
                droppedEvents,
                truncatedToolOutputs,
                droppedEvents == null ? null : droppedEvents == 0);
    }

    private static TraceMetadataDto.Input input(JsonNode node, JsonNode events) {
        if (node != null && !node.isObject()) {
            node = null;
        }
        JsonNode turnContext = lifecycleEventDetails(events, "turn_context");
        TraceMetadataDto.Input projected = new TraceMetadataDto.Input(
                safeText(node, "fingerprint"),
                safeText(node, "questionFingerprint"),
                safeText(node, "pageContextFingerprint"),
                firstNonNullContent(
                        node == null ? null : content(node.get("question")),
                        turnContext == null ? null : content(turnContext.get("question"))),
                firstNonNullContent(
                        node == null ? null : content(node.get("pageContext")),
                        turnContext == null ? null : content(turnContext.get("pageContext"))));
        return allNull(
                projected.fingerprint(),
                projected.questionFingerprint(),
                projected.pageContextFingerprint(),
                projected.question(),
                projected.pageContext())
                ? null
                : projected;
    }

    private static JsonNode lifecycleEventDetails(JsonNode events, String name) {
        if (events == null || !events.isArray()) {
            return null;
        }
        for (JsonNode event : events) {
            if (event != null
                    && event.isObject()
                    && "lifecycle".equals(rawText(event, "type"))
                    && name.equals(rawText(event, "name"))) {
                JsonNode details = event.get("details");
                return details != null && details.isObject() ? details : null;
            }
        }
        return null;
    }

    private static TraceContentDto firstContent(JsonNode node, String... fieldNames) {
        if (node == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            TraceContentDto projected = content(node.get(fieldName));
            if (projected != null) {
                return projected;
            }
        }
        return null;
    }

    private static TraceContentDto firstNonNullContent(TraceContentDto first, TraceContentDto second) {
        return first == null ? second : first;
    }

    private static String contentText(JsonNode node) {
        TraceContentDto projected = content(node);
        if (projected != null) {
            return projected.text();
        }
        if (node == null || !node.isTextual()) {
            return null;
        }
        String safe = AgentTraceSanitizer.safeMessage(node.textValue());
        return safe.isBlank() ? null : safe;
    }

    private static TraceContentDto content(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String raw = rawText(node, "text");
        if (raw == null) {
            return null;
        }
        boolean additionallyTruncated = raw.length() > MAX_CONTENT_FIELD_CHARS;
        String text = additionallyTruncated ? raw.substring(0, MAX_CONTENT_FIELD_CHARS) : raw;
        Integer sourceChars = nonNegativeInteger(node, "sourceChars");
        String contentHash = sha256(node, "sha256");
        Boolean persistedTruncated = booleanValue(node, "truncated");
        Boolean credentialRedacted = booleanValue(node, "credentialRedacted");
        return new TraceContentDto(
                text,
                sourceChars == null ? raw.length() : sourceChars,
                contentHash,
                additionallyTruncated || Boolean.TRUE.equals(persistedTruncated),
                Boolean.TRUE.equals(credentialRedacted));
    }

    private static JsonParseResult parseObject(
            String raw,
            ObjectMapper objectMapper,
            String correlationId,
            String field) {
        if (raw == null || raw.isBlank()) {
            return JsonParseResult.invalid();
        }
        try {
            JsonNode value = objectMapper.readTree(raw);
            return value != null && value.isObject()
                    ? JsonParseResult.valid(value)
                    : JsonParseResult.invalid();
        } catch (Exception exception) {
            log.debug(
                    "Trace detail {} parse failed for correlationId={} ({})",
                    field,
                    boundedIdentifier(correlationId),
                    exception.getClass().getName());
            return JsonParseResult.invalid();
        }
    }

    private static JsonArrayParseResult parseArray(
            String raw,
            ObjectMapper objectMapper,
            String correlationId,
            String field) {
        if (raw == null || raw.isBlank()) {
            return JsonArrayParseResult.valid(objectMapper.createArrayNode());
        }
        try {
            JsonNode value = objectMapper.readTree(raw);
            return value != null && value.isArray()
                    ? JsonArrayParseResult.valid(value)
                    : JsonArrayParseResult.invalid();
        } catch (Exception exception) {
            log.debug(
                    "Trace detail {} parse failed for correlationId={} ({})",
                    field,
                    boundedIdentifier(correlationId),
                    exception.getClass().getName());
            return JsonArrayParseResult.invalid();
        }
    }

    private static Map<String, Object> sanitizedInput(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, Object> projected = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext() && projected.size() < MAX_ITEMS) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            if (key == null || key.isBlank() || forbiddenInputKey(key)) {
                continue;
            }
            Object value = safeInputValue(entry.getValue());
            if (value != null || entry.getValue().isNull()) {
                projected.put(AgentTraceSanitizer.safeMessage(key), value);
            }
        }
        return Collections.unmodifiableMap(projected);
    }

    private static Map<String, Object> adminAttributes(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, Object> projected = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext() && projected.size() < MAX_ATTRIBUTE_ENTRIES) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey() == null
                    ? ""
                    : AgentTraceSanitizer.safeMessage(entry.getKey()).strip();
            if (key.isBlank()) {
                continue;
            }
            projected.put(key, sensitiveKey(key)
                    ? "[REDACTED]"
                    : adminAttributeValue(entry.getValue(), 0));
        }
        return projected.isEmpty() ? null : Collections.unmodifiableMap(projected);
    }

    private static Object adminAttributeValue(JsonNode value, int depth) {
        if (depth >= MAX_ATTRIBUTE_DEPTH) {
            return "[TRUNCATED_DEPTH]";
        }
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        if (value.isFloatingPointNumber()) {
            double number = value.doubleValue();
            return Double.isFinite(number) ? number : null;
        }
        if (value.isTextual()) {
            return AgentTraceSanitizer.captureAdminContent(
                    value.textValue(), MAX_ATTRIBUTE_STRING_CHARS).text();
        }
        if (value.isArray()) {
            List<Object> projected = new ArrayList<>();
            int limit = Math.min(value.size(), MAX_ATTRIBUTE_ENTRIES);
            for (int index = 0; index < limit; index++) {
                projected.add(adminAttributeValue(value.get(index), depth + 1));
            }
            return Collections.unmodifiableList(projected);
        }
        if (value.isObject()) {
            Map<String, Object> projected = new LinkedHashMap<>();
            var fields = value.fields();
            while (fields.hasNext() && projected.size() < MAX_ATTRIBUTE_ENTRIES) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey() == null
                        ? ""
                        : AgentTraceSanitizer.safeMessage(entry.getKey()).strip();
                if (key.isBlank()) {
                    continue;
                }
                projected.put(key, sensitiveKey(key)
                        ? "[REDACTED]"
                        : adminAttributeValue(entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(projected);
        }
        return null;
    }

    private static boolean sensitiveKey(String key) {
        return AgentTraceSanitizer.isAdminCredentialKey(key);
    }

    private static Object safeInputValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        if (value.isFloatingPointNumber()) {
            double number = value.doubleValue();
            return Double.isFinite(number) ? number : null;
        }
        if (value.isTextual()) {
            return AgentTraceSanitizer.safeSanitizedInputText(value.textValue());
        }
        if (value.isArray()) {
            List<Object> items = new ArrayList<>();
            int limit = Math.min(value.size(), MAX_ITEMS);
            for (int index = 0; index < limit; index++) {
                Object item = safeInputValue(value.get(index));
                if (item != null || value.get(index).isNull()) {
                    items.add(item);
                }
            }
            return Collections.unmodifiableList(items);
        }
        return null;
    }

    private static boolean forbiddenInputKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return INTERNAL_INPUT_KEYS.contains(normalized)
                || sensitiveKey(normalized);
    }

    private static List<String> safeStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> projected = new ArrayList<>();
        int limit = Math.min(node.size(), MAX_ITEMS);
        for (int index = 0; index < limit; index++) {
            JsonNode item = node.get(index);
            if (item != null && item.isTextual()) {
                String text = AgentTraceSanitizer.safeMessage(item.textValue());
                if (!text.isBlank()) {
                    projected.add(text);
                }
            }
        }
        return List.copyOf(projected);
    }

    private static String strictSummary(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null
                    && value.isTextual()
                    && value.textValue().length() <= 128
                    && LEGACY_SUMMARY.matcher(value.textValue()).matches()) {
                return value.textValue();
            }
        }
        return null;
    }

    private static String sha256(JsonNode node, String... fieldNames) {
        String value = rawText(node, fieldNames);
        return value != null && SHA_256.matcher(value).matches() ? value.toLowerCase(Locale.ROOT) : null;
    }

    private static String safePreview(JsonNode node, String... fieldNames) {
        String value = rawText(node, fieldNames);
        return value == null ? null : AgentTraceSanitizer.preview(value).text();
    }

    private static String safeText(JsonNode node, String... fieldNames) {
        String value = rawText(node, fieldNames);
        if (value == null || value.isBlank()) {
            return null;
        }
        String safe = AgentTraceSanitizer.safeMessage(value.strip());
        return safe.isBlank() ? null : safe;
    }

    private static String stableCode(JsonNode node, String... fieldNames) {
        String value = rawText(node, fieldNames);
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return STABLE_CODE.matcher(stripped).matches() ? stripped : null;
    }

    private static String rawText(JsonNode node, String... fieldNames) {
        if (node == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isTextual()) {
                return value.textValue();
            }
        }
        return null;
    }

    private static Integer positiveInteger(JsonNode node, String... fieldNames) {
        Integer value = nonNegativeInteger(node, fieldNames);
        return value != null && value > 0 ? value : null;
    }

    private static Integer nonNegativeInteger(JsonNode node, String... fieldNames) {
        JsonNode value = first(node, fieldNames);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            return null;
        }
        int projected = value.intValue();
        return projected >= 0 ? projected : null;
    }

    private static Long requiredNonNegativeLong(JsonNode node, String... fieldNames) {
        return nonNegativeLong(node, fieldNames);
    }

    private static Long nonNegativeLong(JsonNode node, String... fieldNames) {
        JsonNode value = first(node, fieldNames);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            return null;
        }
        long projected = value.longValue();
        return projected >= 0 ? projected : null;
    }

    private static Boolean booleanValue(JsonNode node, String... fieldNames) {
        JsonNode value = first(node, fieldNames);
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    private static JsonNode first(JsonNode node, String... fieldNames) {
        if (node == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private static String eventId(String correlationId, String suffix) {
        return boundedIdentifier(correlationId) + ":" + suffix;
    }

    private static String boundedIdentifier(String value) {
        String safe = AgentTraceSanitizer.safeMessage(value == null ? "trace" : value);
        return safe.isBlank() ? "trace" : safe;
    }

    private static boolean allNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return false;
            }
        }
        return true;
    }

    private static String defaultCode(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static <T> List<T> defaultList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record PayloadProjection(
            TraceDetailDto.Compatibility compatibility,
            TraceDetailDto.TimingAccuracy timingAccuracy,
            List<TracePhaseDto> phases,
            TraceMetadataDto metadata) {

        private static PayloadProjection unsupported() {
            return new PayloadProjection(
                    TraceDetailDto.Compatibility.UNSUPPORTED,
                    TraceDetailDto.TimingAccuracy.NONE,
                    List.of(),
                    null);
        }

        private static PayloadProjection malformed() {
            return new PayloadProjection(
                    TraceDetailDto.Compatibility.MALFORMED,
                    TraceDetailDto.TimingAccuracy.NONE,
                    List.of(),
                    null);
        }
    }

    private record JsonParseResult(boolean valid, JsonNode value) {
        private static JsonParseResult valid(JsonNode value) {
            return new JsonParseResult(true, value);
        }

        private static JsonParseResult invalid() {
            return new JsonParseResult(false, null);
        }
    }

    private record JsonArrayParseResult(boolean valid, JsonNode value) {
        private static JsonArrayParseResult valid(JsonNode value) {
            return new JsonArrayParseResult(true, value);
        }

        private static JsonArrayParseResult invalid() {
            return new JsonArrayParseResult(false, null);
        }
    }
}
