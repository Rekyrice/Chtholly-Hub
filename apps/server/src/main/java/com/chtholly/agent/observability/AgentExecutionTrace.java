package com.chtholly.agent.observability;

import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.runtime.AgentTurnControl;
import com.chtholly.agent.runtime.AgentToolResult;
import com.chtholly.agent.trace.TraceStatus;
import com.chtholly.common.tracing.CorrelationIdSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.TreeMap;

/** 单次 Agent 执行的可观测性追踪（轻量，无 OTel）。 */
@Slf4j
@Getter
public class AgentExecutionTrace {

    private static final int EVENT_LIMIT = 256;
    private static final int MAX_EVENT_TEXT_CHARS = AgentTraceSanitizer.MAX_INPUT_STRING_CHARS;
    private static final int MAX_ADMIN_DIAGNOSTIC_ITEMS = 64;
    private static final int MAX_ADMIN_DIAGNOSTIC_DEPTH = 6;
    private static final int MAX_ADMIN_DIAGNOSTIC_TEXT_CHARS = 16_384;

    private final String correlationId;
    private final long userId;
    private final String sessionId;
    private int maxSteps;
    private final String requestId;
    private final String turnId;
    private final String connectionId;
    private final AgentTurnControl turnControl;
    private long turnBudgetMs;
    private final long startedAtMs = System.currentTimeMillis();
    private final Instant startedAt = Instant.ofEpochMilli(startedAtMs);

    private int totalSteps;
    private int llmCalls;
    private long llmDurationMs;
    private long toolDurationMs;
    private long inputTokenEstimate;
    private long outputTokenEstimate;
    private int finalAnswerLength;
    private int eventSequence;
    private int droppedEvents;
    private int truncatedToolOutputs;
    private int capturedChars;
    private int truncatedCaptureFields;
    private int credentialRedactions;
    private String terminatedBy = "error";
    private String modelVersion = "unknown";
    private String runMode = "candidate";
    private String questionFingerprint = "";
    private String pageContextFingerprint = "";
    private String inputFingerprint = "";
    private String skillSelectionStatus = "NOT_EVALUATED";
    private String skillId = "";
    private String skillVersion = "";
    private String skillValidationStatus = "NOT_RUN";
    private Map<String, String> retrievalStatuses = Map.of();
    private int evidenceCount;
    private String evidenceSnapshotHash = "";
    private List<Map<String, String>> evidenceMetadata = List.of();
    private String citationValidationStatus = "NOT_RUN";
    private Map<String, String> toolVersions = Map.of();
    private String toolPlanReason = "not_planned";
    private List<String> effectiveTools = List.of();
    private String timeoutStage = "";
    private boolean cancelled;
    private Long modelFirstTokenMs;
    private Long safeAnswerReadyMs;
    private Long firstClientDeltaMs;
    private String clientDeliveryStatus;
    private String clientTerminalType = "";
    private String clientDeliveryCode = "";
    private String memoryWriteStatus = "NOT_ATTEMPTED";
    private String memoryFailureCode = "";
    private FailureType failureType = FailureType.NONE;
    private OutcomeReason outcomeReason = OutcomeReason.NONE;

    @Setter
    private String errorMessage;

    private Long finishedAtMs;
    private Long durationMs;
    private TraceStatus status;

    private final Set<String> toolsCalled = new LinkedHashSet<>();
    private final List<String> stepActions = new ArrayList<>();
    private final List<TraceStepInfo> steps = new ArrayList<>();
    private final List<TraceToolCallInfo> toolCallDetails = new ArrayList<>();
    private final List<TraceLlmCallInfo> llmCallDetails = new ArrayList<>();
    private final List<TraceEventInfo> events = new ArrayList<>();

    public AgentExecutionTrace(long userId, String sessionId, int maxSteps) {
        this.correlationId = resolveCorrelationId();
        this.userId = userId;
        this.sessionId = sessionId;
        this.maxSteps = maxSteps;
        this.requestId = "";
        this.turnId = "";
        this.connectionId = "";
        this.turnControl = null;
        this.turnBudgetMs = 0;
        this.clientDeliveryStatus = AgentTurnControl.ClientDeliveryStatus.NOT_APPLICABLE.name();
    }

    /** Creates a trace rooted at a canonical server turn identity. */
    public AgentExecutionTrace(long userId, AgentTurnControl control, int maxSteps) {
        AgentTurnControl safeControl = java.util.Objects.requireNonNull(control, "control");
        this.correlationId = normalizeTurnId(safeControl.turnId());
        this.userId = userId;
        this.sessionId = safeControl.chatSessionId();
        this.maxSteps = maxSteps;
        this.requestId = safeControl.requestId();
        this.turnId = safeControl.turnId();
        this.connectionId = safeControl.connectionId();
        this.turnControl = safeControl;
        this.turnBudgetMs = safeControl.budget().totalBudget().toMillis();
        this.clientDeliveryStatus = safeControl.clientDeliveryStatus().name();
    }

    /** Narrows the recorded step budget after a selected skill applies its execution cap. */
    public void limitMaxSteps(int effectiveMaxSteps) {
        this.maxSteps = Math.min(maxSteps, Math.max(1, effectiveMaxSteps));
    }

    private static String resolveCorrelationId() {
        String mdcId = MDC.get(CorrelationIdSupport.MDC_CORRELATION_ID);
        if (mdcId != null && !mdcId.isBlank() && !CorrelationIdSupport.DEFAULT_ID.equals(mdcId)) {
            return mdcId.replace("-", "");
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String normalizeTurnId(String turnId) {
        String normalized = turnId == null ? "" : turnId.replaceAll("[^A-Za-z0-9]", "");
        return normalized.isBlank() ? resolveCorrelationId() : normalized;
    }

    public void recordLlmCall(long durationMs, int inputChars, int outputChars) {
        recordLlmCall(durationMs, inputChars, outputChars, null);
    }

    public void recordLlmCall(long durationMs, int inputChars, int outputChars, Long firstTokenMs) {
        recordLlmCall(null, durationMs, inputChars, outputChars, firstTokenMs);
    }

    /**
     * Records one model invocation with its explicit loop step association.
     *
     * @param stepIndex zero-based loop step, or {@code null} when unavailable
     * @param durationMs model duration
     * @param inputChars model input size
     * @param outputChars model output size
     * @param firstTokenMs first-token latency, or {@code null}
     */
    public void recordLlmCall(
            Integer stepIndex,
            long durationMs,
            int inputChars,
            int outputChars,
            Long firstTokenMs) {
        recordLlmCall(
                stepIndex,
                "LEGACY",
                modelVersion,
                "SUCCESS",
                "",
                1,
                0,
                0,
                durationMs,
                inputChars,
                outputChars,
                firstTokenMs);
    }

    /**
     * Records one model invocation using the Trace v4 duration event contract.
     *
     * @param stepIndex zero-based loop step, or {@code null} when unavailable
     * @param purpose stable invocation purpose
     * @param model model identifier
     * @param status stable invocation status
     * @param errorCode stable low-cardinality error code
     * @param attempt one-based attempt number
     * @param budgetBeforeMs remaining turn budget before the invocation
     * @param budgetAfterMs remaining turn budget after the invocation
     * @param durationMs invocation duration
     * @param inputChars model input size
     * @param outputChars model output size
     * @param firstTokenMs first-token latency, or {@code null}
     */
    public void recordLlmCall(
            Integer stepIndex,
            String purpose,
            String model,
            String status,
            String errorCode,
            int attempt,
            long budgetBeforeMs,
            long budgetAfterMs,
            long durationMs,
            int inputChars,
            int outputChars,
            Long firstTokenMs) {
        recordLlmCall(
                stepIndex,
                purpose,
                model,
                status,
                errorCode,
                attempt,
                budgetBeforeMs,
                budgetAfterMs,
                durationMs,
                inputChars,
                outputChars,
                firstTokenMs,
                null);
    }

    /** Records a model invocation together with administrator-only replay content. */
    public void recordLlmCall(
            Integer stepIndex,
            String purpose,
            String model,
            String status,
            String errorCode,
            int attempt,
            long budgetBeforeMs,
            long budgetAfterMs,
            long durationMs,
            int inputChars,
            int outputChars,
            Long firstTokenMs,
            LlmExchange exchange) {
        long safeDurationMs = nonNegative(durationMs);
        long safeBudgetBeforeMs = nonNegative(budgetBeforeMs);
        long safeBudgetAfterMs = nonNegative(budgetAfterMs);
        int safeInputChars = Math.max(0, inputChars);
        int safeOutputChars = Math.max(0, outputChars);
        int safeAttempt = Math.max(1, attempt);
        Integer safeStepIndex = nonNegativeStepIndex(stepIndex);
        Long safeFirstTokenMs = nonNegative(firstTokenMs);
        String safePurpose = eventText(purpose, "UNKNOWN");
        String safeModel = eventText(model, "unknown");
        String safeStatus = stableCode(status, "UNKNOWN");
        String safeErrorCode = stableCode(errorCode, "");
        int sequence = nextEventSequence();

        llmCalls++;
        llmDurationMs += safeDurationMs;
        inputTokenEstimate += estimateTokens(safeInputChars);
        outputTokenEstimate += estimateTokens(safeOutputChars);
        llmCallDetails.add(new TraceLlmCallInfo(
                sequence,
                safeStepIndex,
                safePurpose,
                safeModel,
                safeStatus,
                safeErrorCode,
                safeAttempt,
                safeBudgetBeforeMs,
                safeBudgetAfterMs,
                safeDurationMs,
                safeInputChars,
                safeOutputChars,
                safeFirstTokenMs));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("purpose", safePurpose);
        details.put("model", safeModel);
        details.put("input_chars", safeInputChars);
        details.put("output_chars", safeOutputChars);
        if (safeFirstTokenMs != null) {
            details.put("first_token_ms", safeFirstTokenMs);
        }
        if (exchange != null) {
            details.put("systemPrompt", captureContent(exchange.systemPrompt()));
            details.put("userPrompt", captureContent(exchange.userPrompt()));
            details.put("rawOutput", captureContent(exchange.rawOutput()));
            if (exchange.failureClass() != null && !exchange.failureClass().isBlank()) {
                details.put("failureClass", captureContent(exchange.failureClass()));
            }
            if (exchange.failureMessage() != null && !exchange.failureMessage().isBlank()) {
                details.put("failureMessage", captureContent(exchange.failureMessage()));
            }
        }
        recordDurationEvent(
                sequence,
                "llm",
                "llm",
                "llm_call",
                safeStatus,
                safeStepIndex,
                safeAttempt,
                safeBudgetBeforeMs,
                safeBudgetAfterMs,
                safeErrorCode,
                safeDurationMs,
                details);
    }

    public void recordToolCall(
            String toolName,
            long durationMs,
            String inputSummary,
            String observation,
            boolean success) {
        recordToolCall(null, toolName, durationMs, inputSummary, observation, success);
    }

    /**
     * Records one tool invocation with a bounded input and observation summary.
     *
     * @param stepIndex zero-based loop step, or {@code null} when unavailable
     * @param toolName tool identifier
     * @param durationMs tool duration
     * @param inputSummary raw input summary
     * @param observation raw tool observation
     * @param success explicit execution outcome
     */
    public void recordToolCall(
            Integer stepIndex,
            String toolName,
            long durationMs,
            String inputSummary,
            String observation,
            boolean success) {
        String safeToolName = eventText(toolName, "unknown");
        long safeDurationMs = nonNegative(durationMs);
        Integer safeStepIndex = nonNegativeStepIndex(stepIndex);
        int sequence = nextEventSequence();
        if (!safeToolName.isBlank()) {
            toolsCalled.add(safeToolName);
        }
        toolDurationMs += safeDurationMs;
        toolCallDetails.add(new TraceToolCallInfo(
                sequence,
                safeStepIndex,
                safeToolName,
                fingerprintSummary(inputSummary),
                fingerprintSummary(observation),
                safeDurationMs,
                success,
                success ? AgentToolResult.Status.SUCCESS.name() : AgentToolResult.Status.ERROR.name(),
                success ? "" : "TOOL_FAILED",
                0,
                0));
        recordDurationEvent(
                sequence,
                "tool",
                "tool",
                safeToolName,
                success ? "SUCCESS" : "ERROR",
                safeStepIndex,
                1,
                0L,
                0L,
                success ? "" : "TOOL_FAILED",
                safeDurationMs,
                Map.of());
    }

    /**
     * Records one structured tool result without retaining its raw observation.
     *
     * @param stepIndex zero-based loop step, or {@code null} when unavailable
     * @param toolName tool identifier
     * @param durationMs tool duration
     * @param budgetBeforeMs remaining turn budget before the call
     * @param budgetAfterMs remaining turn budget after the call
     * @param result structured tool result with bounded diagnostics
     */
    public void recordToolCall(
            Integer stepIndex,
            String toolName,
            long durationMs,
            long budgetBeforeMs,
            long budgetAfterMs,
            AgentToolResult result) {
        recordToolCall(
                stepIndex,
                toolName,
                durationMs,
                budgetBeforeMs,
                budgetAfterMs,
                result,
                null,
                null);
    }

    /** Records a tool invocation with the actual augmented input and final Observe content. */
    public void recordToolCall(
            Integer stepIndex,
            String toolName,
            long durationMs,
            long budgetBeforeMs,
            long budgetAfterMs,
            AgentToolResult result,
            String actualInput,
            String finalObservation) {
        AgentToolResult safeResult = java.util.Objects.requireNonNull(result, "result");
        AgentToolDiagnostics diagnostics = safeResult.diagnostics();
        String safeToolName = eventText(toolName, "unknown");
        String safeStatus = safeResult.status().name();
        String safeErrorCode = stableCode(safeResult.errorCode(), "");
        long safeDurationMs = nonNegative(durationMs);
        long safeBudgetBeforeMs = nonNegative(budgetBeforeMs);
        long safeBudgetAfterMs = nonNegative(budgetAfterMs);
        Integer safeStepIndex = nonNegativeStepIndex(stepIndex);
        int sequence = nextEventSequence();
        boolean success = safeResult.status() == AgentToolResult.Status.SUCCESS;

        toolsCalled.add(safeToolName);
        toolDurationMs += safeDurationMs;
        toolCallDetails.add(new TraceToolCallInfo(
                sequence,
                safeStepIndex,
                safeToolName,
                fingerprintSummary(String.valueOf(diagnostics.sanitizedInput())),
                fingerprintSummary(safeResult.observation()),
                safeDurationMs,
                success,
                safeStatus,
                safeErrorCode,
                safeBudgetBeforeMs,
                safeBudgetAfterMs));
        if (diagnostics.outputTruncated()) {
            truncatedToolOutputs++;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("operation", diagnostics.operation());
        details.put("provider", diagnostics.provider());
        details.put("sourcePolicy", diagnostics.sourcePolicy());
        details.put("sanitizedInput", diagnostics.sanitizedInput());
        details.put("outputPreview", diagnostics.outputPreview());
        details.put("outputSha256", diagnostics.outputSha256());
        details.put("outputChars", diagnostics.outputChars());
        details.put("outputTruncated", diagnostics.outputTruncated());
        if (diagnostics.resultCount() != null) {
            details.put("resultCount", diagnostics.resultCount());
        }
        details.put("selectedIds", diagnostics.selectedIds());
        details.put("attributes", diagnostics.attributes());
        if (actualInput != null) {
            details.put("input", captureContent(actualInput));
        }
        if (finalObservation != null) {
            details.put("observation", captureContent(finalObservation));
        }
        recordDurationEvent(
                sequence,
                "tool",
                "tool",
                safeToolName,
                safeStatus,
                safeStepIndex,
                1,
                safeBudgetBeforeMs,
                safeBudgetAfterMs,
                safeErrorCode,
                safeDurationMs,
                details);
    }

    public void recordStep(int stepIndex, String action, long stepLlmMs, long stepToolMs) {
        int safeStepIndex = Math.max(0, stepIndex);
        String safeAction = eventText(action, "unknown");
        long safeStepLlmMs = nonNegative(stepLlmMs);
        long safeStepToolMs = nonNegative(stepToolMs);
        totalSteps = safeStepIndex + 1;
        stepActions.add(safeAction);
        steps.add(new TraceStepInfo(safeStepIndex, safeAction, safeStepLlmMs, safeStepToolMs));
        log.info("[Agent] Step {}/{}: action={}, llm_ms={}, tool_ms={}",
                safeStepIndex + 1, maxSteps, safeAction, safeStepLlmMs, safeStepToolMs);
    }

    /** Records bounded replay input plus stable component mode without placing it on OTel spans. */
    public void recordTurnContext(
            String question,
            String pageContext,
            String modelVersion,
            String runMode) {
        String normalizedQuestion = question == null ? "" : question.strip();
        String normalizedPage = pageContext == null ? "" : pageContext.strip();
        this.questionFingerprint = sha256(normalizedQuestion);
        this.pageContextFingerprint = sha256(normalizedPage);
        this.inputFingerprint = sha256(normalizedQuestion + "\n--page--\n" + normalizedPage);
        this.modelVersion = safe(modelVersion, "unknown");
        String normalizedRunMode = safe(runMode, "candidate")
                .toLowerCase(java.util.Locale.ROOT);
        this.runMode = switch (normalizedRunMode) {
            case "baseline", "replay" -> normalizedRunMode;
            default -> "candidate";
        };
        recordInstantEvent(
                "accepted",
                "lifecycle",
                "turn_context",
                "ACCEPTED",
                Map.of(
                        "model", this.modelVersion,
                        "run_mode", this.runMode,
                        "question", captureContent(normalizedQuestion),
                        "pageContext", captureContent(normalizedPage)));
    }

    public void recordSkillSelection(String status, String id, String version) {
        skillSelectionStatus = safe(status, "NOT_EVALUATED");
        skillId = safe(id, "");
        skillVersion = safe(version, "");
        recordInstantEvent(
                "skill",
                "lifecycle",
                "skill_selection",
                stableCode(skillSelectionStatus, "UNKNOWN"),
                Map.of("skill_id", skillId, "skill_version", skillVersion));
    }

    public void recordSkillValidation(String status) {
        skillValidationStatus = safe(status, "NOT_RUN");
        recordInstantEvent(
                "skill",
                "lifecycle",
                "skill_validation",
                stableCode(skillValidationStatus, "UNKNOWN"),
                Map.of());
    }

    /** Records retrieval metadata and version-bound Evidence without persisting titles or excerpts. */
    public void recordRetrieval(Map<String, String> statuses, EvidenceSet evidenceSet) {
        TreeMap<String, String> sortedStatuses = new TreeMap<>();
        if (statuses != null) {
            statuses.forEach((key, value) -> {
                if (key != null && !key.isBlank()) {
                    sortedStatuses.put(key, safe(value, "UNKNOWN"));
                }
            });
        }
        retrievalStatuses = Map.copyOf(sortedStatuses);
        EvidenceSet evidence = evidenceSet == null ? EvidenceSet.empty() : evidenceSet;
        evidenceCount = evidence.items().size();
        evidenceSnapshotHash = evidence.contentHash();
        evidenceMetadata = evidence.items().stream().map(item -> {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("citationId", safe(item.citationId(), ""));
            metadata.put("documentId", safe(item.documentId(), ""));
            metadata.put("source", safe(item.retrievalSource(), ""));
            metadata.put("sourceVersion", safe(item.sourceVersion(), ""));
            metadata.put("sourceHash", safe(item.sourceHash(), ""));
            return Map.copyOf(metadata);
        }).toList();
        recordInstantEvent(
                "retrieval",
                "lifecycle",
                "retrieval",
                retrievalStatuses.values().stream().anyMatch(value -> "FAILED".equals(value) || "TIMEOUT".equals(value))
                        ? "DEGRADED"
                        : "COMPLETE",
                Map.of("source_count", retrievalStatuses.size(), "evidence_count", evidenceCount));
    }

    public void recordCitationValidation(String status) {
        citationValidationStatus = safe(status, "NOT_RUN");
        recordInstantEvent(
                "validation",
                "lifecycle",
                "citation_validation",
                stableCode(citationValidationStatus, "UNKNOWN"),
                Map.of());
    }

    public void recordTools(Set<String> toolNames) {
        TreeMap<String, String> versions = new TreeMap<>();
        if (toolNames != null) {
            toolNames.stream()
                    .filter(name -> name != null && !name.isBlank())
                    .map(name -> eventText(name, "unknown"))
                    .forEach(name -> versions.put(name, AgentComponentVersions.TOOLS));
        }
        toolVersions = Map.copyOf(versions);
    }

    /** Records the deterministic tool-planning decision without raw question text. */
    public void recordToolPlan(String reason, Set<String> toolNames) {
        toolPlanReason = safe(reason, "not_planned");
        effectiveTools = toolNames == null
                ? List.of()
                : toolNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> eventText(name, "unknown"))
                .sorted()
                .toList();
        recordInstantEvent(
                "plan",
                "lifecycle",
                "tool_plan",
                "PLANNED",
                Map.of("reason", toolPlanReason, "tool_count", effectiveTools.size()));
    }

    /** Updates the effective whole-turn budget after a selected Skill tightens the global limit. */
    public void recordTurnBudget(java.time.Duration budget) {
        turnBudgetMs = budget == null ? 0 : Math.max(0, budget.toMillis());
        recordInstantEvent(
                "accepted",
                "lifecycle",
                "turn_budget",
                "UPDATED",
                Map.of("budget_ms", turnBudgetMs));
    }

    /** Records the stage that exhausted the shared turn budget. */
    public void recordTimeoutStage(String stage) {
        timeoutStage = safe(stage, "unknown");
        recordInstantEvent(
                "delivery",
                "lifecycle",
                "timeout_stage",
                "TIMEOUT",
                Map.of("stage", timeoutStage));
    }

    /** Records cooperative cancellation state at trace finalization. */
    public void recordCancellation(boolean cancelled) {
        this.cancelled = cancelled;
        recordInstantEvent(
                "delivery",
                "lifecycle",
                "cancellation",
                cancelled ? "CANCELLED" : "ACTIVE",
                Map.of());
    }

    /** Records the low-cardinality outcome of the fenced conversation-memory write. */
    public void recordMemoryWrite(String status, String failureCode) {
        memoryWriteStatus = safe(status, "UNKNOWN");
        memoryFailureCode = stableCode(failureCode, "");
        recordInstantEvent(
                "memory",
                "lifecycle",
                "memory_write",
                stableCode(memoryWriteStatus, "UNKNOWN"),
                memoryFailureCode.isBlank() ? Map.of() : Map.of("error_code", memoryFailureCode));
    }

    /** Reconciles a completed transport terminal outcome before logging or persistence. */
    public void resolveClientDelivery() {
        if (turnControl == null) {
            recordInstantEvent(
                    "delivery",
                    "lifecycle",
                    "client_delivery",
                    AgentTurnControl.ClientDeliveryStatus.NOT_APPLICABLE.name(),
                    Map.of());
            return;
        }
        AgentTurnControl.ClientDeliveryStatus deliveryStatus = turnControl.clientDeliveryStatus();
        if (deliveryStatus == AgentTurnControl.ClientDeliveryStatus.PENDING) {
            throw new IllegalStateException("Client delivery is unresolved for turn " + turnId);
        }
        clientDeliveryStatus = deliveryStatus.name();
        clientTerminalType = turnControl.clientTerminalType();
        clientDeliveryCode = turnControl.clientDeliveryCode();

        boolean deliveryInvalidatesSuccess = status == TraceStatus.SUCCESS
                && (deliveryStatus == AgentTurnControl.ClientDeliveryStatus.FAILED
                        || "error".equals(clientTerminalType));
        if (!deliveryInvalidatesSuccess) {
            recordClientDeliveryEvent();
            return;
        }
        terminatedBy = "error";
        status = TraceStatus.FAILURE;
        if ("TURN_COORDINATION_UNAVAILABLE".equals(clientDeliveryCode)) {
            failureType = FailureType.TURN_COORDINATION_FAILED;
        } else {
            failureType = FailureType.CLIENT_DELIVERY_FAILED;
        }
        if (errorMessage == null || errorMessage.isBlank()) {
            errorMessage = clientDeliveryCode.isBlank()
                    ? "CLIENT_DELIVERY_FAILED"
                    : clientDeliveryCode;
        }
        recordClientDeliveryEvent();
    }

    /** Rejects persistence before the transport boundary has reconciled this trace. */
    public void requireClientDeliveryResolved() {
        if (AgentTurnControl.ClientDeliveryStatus.PENDING.name().equals(clientDeliveryStatus)) {
            throw new IllegalStateException("Client delivery was not reconciled for turn " + turnId);
        }
    }

    /** Records model, validation, and client-visible answer milestones relative to turn start. */
    public void recordAnswerTiming(
            Long modelFirstTokenMs,
            Long safeAnswerReadyMs,
            Long firstClientDeltaMs) {
        this.modelFirstTokenMs = nonNegative(modelFirstTokenMs);
        this.safeAnswerReadyMs = nonNegative(safeAnswerReadyMs);
        this.firstClientDeltaMs = nonNegative(firstClientDeltaMs);
        Map<String, Object> details = new LinkedHashMap<>();
        if (this.modelFirstTokenMs != null) {
            details.put("model_first_token_ms", this.modelFirstTokenMs);
        }
        if (this.safeAnswerReadyMs != null) {
            details.put("safe_answer_ready_ms", this.safeAnswerReadyMs);
        }
        if (this.firstClientDeltaMs != null) {
            details.put("first_client_delta_ms", this.firstClientDeltaMs);
        }
        recordInstantEvent("delivery", "lifecycle", "answer_timing", "RECORDED", details);
    }

    public void markFailure(FailureType type) {
        failureType = type == null ? FailureType.INTERNAL_ERROR : type;
        recordInstantEvent(
                "validation",
                "lifecycle",
                "failure_classification",
                failureType.name(),
                Map.of());
    }

    public void recordOutcomeReason(OutcomeReason reason) {
        outcomeReason = reason == null ? OutcomeReason.NONE : reason;
        recordInstantEvent(
                "validation",
                "lifecycle",
                "outcome_reason",
                outcomeReason.name(),
                Map.of());
    }

    public void terminateFinalAnswer(String answer) {
        terminatedBy = "final_answer";
        finalAnswerLength = answer == null ? 0 : answer.length();
        recordTerminalEvent("SUCCESS", Map.of(
                "answer_chars", finalAnswerLength,
                "answer", captureContent(answer)));
    }

    public void terminateMaxSteps() {
        terminatedBy = "max_steps";
        recordTerminalEvent("ABORTED", Map.of("reason", "max_steps"));
    }

    public void terminateTimeout() {
        terminatedBy = "timeout";
        recordTerminalEvent("TIMEOUT", Map.of("reason", "timeout"));
    }

    public void terminateCancelled() {
        terminatedBy = "cancelled";
        recordTerminalEvent("ABORTED", Map.of("reason", "cancelled"));
    }

    public void terminateError() {
        terminatedBy = "error";
        recordTerminalEvent("FAILURE", Map.of("reason", "error"));
    }

    /** 计算终态与耗时，供持久化使用。 */
    public void finish() {
        finishedAtMs = System.currentTimeMillis();
        durationMs = finishedAtMs - startedAtMs;
        status = mapStatus(terminatedBy);
    }

    public Instant getFinishedAt() {
        return finishedAtMs == null ? null : Instant.ofEpochMilli(finishedAtMs);
    }

    public void finishAndLog(ObjectMapper objectMapper, AgentMetrics metrics) {
        if (finishedAtMs == null) {
            finish();
        }
        Map<String, Object> summary = new LinkedHashMap<>(buildSummaryMap());
        summary.remove("userId");
        summary.remove("sessionId");
        summary.put("correlationId", correlationId);
        summary.put("status", status == null ? null : status.name());

        try {
            log.info("{}", objectMapper.writeValueAsString(summary));
        } catch (Exception e) {
            log.info("agent_execution_complete correlationId={} terminatedBy={} durationMs={}",
                    correlationId, terminatedBy, durationMs);
        }

        if (metrics != null) {
            metrics.recordExecution(durationMs == null ? 0 : durationMs, llmCalls, toolsCalled, terminatedBy);
            recordLatencyMetrics(metrics);
        }
    }

    /** 从 LLM 调用明细中采集 TTFT / TPOT。 */
    private void recordLatencyMetrics(AgentMetrics metrics) {
        for (TraceLlmCallInfo call : llmCallDetails) {
            if (call.firstTokenMs() != null && call.firstTokenMs() >= 0) {
                metrics.recordTtft(call.firstTokenMs());
            }
            long outputTokens = estimateTokens(call.outputChars());
            if (outputTokens > 0 && call.durationMs() > 0) {
                metrics.recordTpot(call.durationMs(), outputTokens);
            }
        }
    }

    public Map<String, Object> toPayloadMap() {
        Map<String, Object> payload = buildSummaryMap();
        payload.put("correlationId", correlationId);
        payload.put("status", status == null ? mapStatus(terminatedBy).name() : status.name());
        payload.put("errorMessage", errorMessage == null ? null : AgentTraceSanitizer.safeMessage(errorMessage));
        payload.put("startedAt", startedAt.toString());
        if (finishedAtMs != null) {
            payload.put("finishedAt", Instant.ofEpochMilli(finishedAtMs).toString());
        }
        payload.put("steps", steps.stream().map(TraceStepInfo::toMap).toList());
        payload.put("toolCalls", toolCallDetails.stream().map(TraceToolCallInfo::toMap).toList());
        payload.put("llmCalls", llmCallDetails.stream().map(TraceLlmCallInfo::toMap).toList());
        payload.put("events", events.stream().map(TraceEventInfo::toMap).toList());
        payload.put("privacy", privacyMetadata());
        payload.put("capture", captureMetadata());
        payload.put("components", componentVersions());
        payload.put("skill", skillMetadata());
        payload.put("retrieval", retrievalMetadata());
        payload.put("toolVersions", toolVersions);
        payload.put("turn", turnMetadata());
        payload.put("memory", memoryMetadata());
        payload.put("toolPlan", toolPlanMetadata());
        payload.put("answerTiming", answerTimingMetadata());
        payload.put("failureType", failureType.name());
        payload.put("outcomeReason", outcomeReason.name());
        payload.put("runMode", runMode);
        payload.put("input", inputMetadata());
        return payload;
    }

    private Map<String, Object> privacyMetadata() {
        Map<String, Object> privacy = new LinkedHashMap<>();
        privacy.put("captureLevel", "ADMIN_FULL");
        privacy.put("policyVersion", "trace-admin-full-v1");
        privacy.put("contentBounded", true);
        privacy.put("maxInputStringChars", AgentTraceSanitizer.MAX_INPUT_STRING_CHARS);
        privacy.put("maxOutputPreviewChars", AgentTraceSanitizer.MAX_OUTPUT_PREVIEW_CHARS);
        privacy.put("maxCollectionItems", AgentTraceSanitizer.MAX_COLLECTION_ITEMS);
        privacy.put("eventLimit", EVENT_LIMIT);
        privacy.put("droppedEvents", droppedEvents);
        privacy.put("truncatedToolOutputs", truncatedToolOutputs);
        return Collections.unmodifiableMap(privacy);
    }

    private Map<String, Object> captureMetadata() {
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("level", "ADMIN_FULL");
        capture.put("policyVersion", "trace-admin-full-v1");
        capture.put("maxPerFieldChars", AgentTraceSanitizer.MAX_ADMIN_CONTENT_CHARS);
        capture.put("maxCapturedChars", AgentTraceSanitizer.MAX_ADMIN_TURN_CAPTURE_CHARS);
        capture.put("capturedChars", capturedChars);
        capture.put("truncated", truncatedCaptureFields > 0);
        capture.put("truncatedFields", truncatedCaptureFields);
        capture.put("redactions", credentialRedactions);
        return Collections.unmodifiableMap(capture);
    }

    private Map<String, String> componentVersions() {
        Map<String, String> components = new LinkedHashMap<>();
        components.put("prompt", AgentComponentVersions.PROMPT);
        components.put("skillSelector", AgentComponentVersions.SKILL_SELECTOR);
        components.put("model", modelVersion);
        components.put("retrieval", AgentComponentVersions.RETRIEVAL);
        components.put("citationValidator", AgentComponentVersions.CITATION_VALIDATOR);
        components.put("tools", AgentComponentVersions.TOOLS);
        components.put("traceSchema", AgentComponentVersions.TRACE_SCHEMA);
        return Map.copyOf(components);
    }

    private Map<String, String> skillMetadata() {
        Map<String, String> skill = new LinkedHashMap<>();
        skill.put("selectionStatus", skillSelectionStatus);
        skill.put("id", skillId);
        skill.put("version", skillVersion);
        skill.put("validationStatus", skillValidationStatus);
        return Map.copyOf(skill);
    }

    private Map<String, Object> retrievalMetadata() {
        Map<String, Object> retrieval = new LinkedHashMap<>();
        retrieval.put("strategy", AgentComponentVersions.RETRIEVAL);
        retrieval.put("statuses", retrievalStatuses);
        retrieval.put("evidenceCount", evidenceCount);
        retrieval.put("evidenceSnapshotHash", evidenceSnapshotHash);
        retrieval.put("evidence", evidenceMetadata);
        retrieval.put("degraded", retrievalStatuses.containsValue("FAILED")
                || retrievalStatuses.containsValue("TIMEOUT"));
        retrieval.put("citationValidationStatus", citationValidationStatus);
        return Map.copyOf(retrieval);
    }

    private Map<String, String> inputMetadata() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("fingerprint", inputFingerprint);
        input.put("questionFingerprint", questionFingerprint);
        input.put("pageContextFingerprint", pageContextFingerprint);
        return Map.copyOf(input);
    }

    private Map<String, Object> turnMetadata() {
        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("requestId", requestId);
        turn.put("turnId", turnId);
        turn.put("chatSessionId", sessionId == null ? "" : sessionId);
        turn.put("connectionId", connectionId);
        turn.put("budgetMs", turnBudgetMs);
        turn.put("maxSteps", maxSteps);
        turn.put("timeoutStage", timeoutStage);
        turn.put("cancelled", cancelled);
        turn.put("clientDeliveryStatus", clientDeliveryStatus);
        turn.put("clientTerminalType", clientTerminalType);
        turn.put("clientDeliveryCode", clientDeliveryCode);
        return Map.copyOf(turn);
    }

    private Map<String, Object> toolPlanMetadata() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("reason", toolPlanReason);
        plan.put("effectiveTools", effectiveTools);
        return Map.copyOf(plan);
    }

    private Map<String, String> memoryMetadata() {
        Map<String, String> memory = new LinkedHashMap<>();
        memory.put("writeStatus", memoryWriteStatus);
        memory.put("failureCode", memoryFailureCode);
        return Map.copyOf(memory);
    }

    private Map<String, Object> answerTimingMetadata() {
        Map<String, Object> timing = new LinkedHashMap<>();
        if (modelFirstTokenMs != null) {
            timing.put("modelFirstTokenMs", modelFirstTokenMs);
        }
        if (safeAnswerReadyMs != null) {
            timing.put("safeAnswerReadyMs", safeAnswerReadyMs);
        }
        if (firstClientDeltaMs != null) {
            timing.put("firstClientDeltaMs", firstClientDeltaMs);
        }
        return Map.copyOf(timing);
    }

    private Map<String, Object> buildSummaryMap() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("event", "agent_execution_complete");
        summary.put("userId", userId);
        if (sessionId != null) {
            summary.put("sessionId", sessionId);
        }
        summary.put("totalSteps", totalSteps);
        summary.put("toolsCalled", new ArrayList<>(toolsCalled));
        summary.put("llmCallCount", llmCalls);
        summary.put("totalDurationMs", durationMs == null ? System.currentTimeMillis() - startedAtMs : durationMs);
        summary.put("llmDurationMs", llmDurationMs);
        summary.put("toolDurationMs", toolDurationMs);
        summary.put("inputTokens", inputTokenEstimate);
        summary.put("outputTokens", outputTokenEstimate);
        summary.put("finalAnswerLength", finalAnswerLength);
        summary.put("terminatedBy", terminatedBy);
        return summary;
    }

    private static TraceStatus mapStatus(String terminatedBy) {
        return switch (terminatedBy) {
            case "final_answer" -> TraceStatus.SUCCESS;
            case "timeout" -> TraceStatus.TIMEOUT;
            case "max_steps", "cancelled" -> TraceStatus.ABORTED;
            default -> TraceStatus.FAILURE;
        };
    }

    private static long estimateTokens(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return Math.max(1, chars / 4L);
    }

    private static String safe(String value, String fallback) {
        String selected = value == null || value.isBlank() ? fallback : value.strip();
        return boundedEventText(selected, MAX_EVENT_TEXT_CHARS);
    }

    private static String boundedEventText(String value, int limit) {
        String selected = value == null ? "" : value;
        String normalized = selected.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("_conversationhistory") || normalized.contains("_userquestion")) {
            return "[REDACTED]";
        }
        return AgentTraceSanitizer.boundedRedactedText(selected, limit);
    }

    private static Long nonNegative(Long value) {
        return value == null || value < 0 ? null : value;
    }

    private static Integer nonNegativeStepIndex(Integer value) {
        return value == null ? null : Math.max(0, value);
    }

    private static long nonNegative(long value) {
        return Math.max(0, value);
    }

    private static String eventText(String value, String fallback) {
        return safe(value, fallback);
    }

    private static String stableCode(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String candidate = AgentTraceSanitizer.boundedRedactedText(value.strip(), 128);
        if (candidate.isBlank() || !candidate.matches("[A-Za-z0-9_.:-]+")) {
            return fallback;
        }
        return candidate;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String fingerprintSummary(String value) {
        String normalized = value == null ? "" : value;
        return "sha256=" + sha256(normalized) + ";chars=" + normalized.length();
    }

    private int nextEventSequence() {
        return ++eventSequence;
    }

    private void recordInstantEvent(
            String phase,
            String type,
            String name,
            String status,
            Map<String, ?> details) {
        recordEvent(
                nextEventSequence(),
                phase,
                type,
                name,
                status,
                null,
                null,
                null,
                null,
                "",
                elapsedMs(),
                0,
                details);
    }

    private void recordDurationEvent(
            int sequence,
            String phase,
            String type,
            String name,
            String status,
            Integer stepIndex,
            Integer attempt,
            Long budgetBeforeMs,
            Long budgetAfterMs,
            String errorCode,
            long durationMs,
            Map<String, ?> details) {
        long safeDurationMs = nonNegative(durationMs);
        long startedOffsetMs = Math.max(0, elapsedMs() - safeDurationMs);
        recordEvent(
                sequence,
                phase,
                type,
                name,
                status,
                stepIndex,
                attempt,
                budgetBeforeMs,
                budgetAfterMs,
                errorCode,
                startedOffsetMs,
                safeDurationMs,
                details);
    }

    private void recordEvent(
            int sequence,
            String phase,
            String type,
            String name,
            String status,
            Integer stepIndex,
            Integer attempt,
            Long budgetBeforeMs,
            Long budgetAfterMs,
            String errorCode,
            long startedOffsetMs,
            long durationMs,
            Map<String, ?> details) {
        if (events.size() >= EVENT_LIMIT) {
            droppedEvents++;
            return;
        }
        events.add(new TraceEventInfo(
                sequence,
                eventText(phase, "delivery"),
                eventText(type, "lifecycle"),
                eventText(name, "unknown"),
                stableCode(status, "UNKNOWN"),
                Math.max(0, startedOffsetMs),
                nonNegative(durationMs),
                stepIndex == null ? null : Math.max(0, stepIndex),
                attempt == null ? null : Math.max(1, attempt),
                budgetBeforeMs == null ? null : nonNegative(budgetBeforeMs),
                budgetAfterMs == null ? null : nonNegative(budgetAfterMs),
                stableCode(errorCode, ""),
                safeDetails(details)));
    }

    private long elapsedMs() {
        return Math.max(0, System.currentTimeMillis() - startedAtMs);
    }

    private void recordClientDeliveryEvent() {
        Map<String, Object> details = new LinkedHashMap<>();
        if (clientTerminalType != null && !clientTerminalType.isBlank()) {
            details.put("terminal_type", clientTerminalType);
        }
        if (clientDeliveryCode != null && !clientDeliveryCode.isBlank()) {
            details.put("delivery_code", clientDeliveryCode);
        }
        recordInstantEvent(
                "delivery",
                "lifecycle",
                "client_delivery",
                stableCode(clientDeliveryStatus, "UNKNOWN"),
                details);
    }

    private void recordTerminalEvent(String terminalStatus, Map<String, ?> details) {
        recordInstantEvent("delivery", "lifecycle", "terminal", terminalStatus, details);
    }

    private Map<String, Object> safeDetails(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> projected = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            if (projected.size() >= AgentTraceSanitizer.MAX_COLLECTION_ITEMS) {
                break;
            }
            String key = eventText(entry.getKey(), "unknown");
            if (AgentTraceSanitizer.isInternalKey(key)) {
                continue;
            }
            if ("attributes".equals(key) && entry.getValue() instanceof Map<?, ?> attributes) {
                projected.put(key, safeAdminAttributes(attributes));
            } else if ("sanitizedInput".equals(key) && entry.getValue() instanceof Map<?, ?> input) {
                projected.put(key, safeSanitizedInput(input));
            } else {
                projected.put(key, AgentTraceSanitizer.isSensitiveKey(key) && isContentValue(entry.getValue())
                        ? "[REDACTED]"
                        : safeDetailValue(key, entry.getValue(), 0));
            }
        }
        return Collections.unmodifiableMap(projected);
    }

    private static Map<String, Object> safeSanitizedInput(Map<?, ?> source) {
        Map<String, Object> projected = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (projected.size() >= AgentTraceSanitizer.MAX_COLLECTION_ITEMS) {
                break;
            }
            String key = eventText(String.valueOf(entry.getKey()), "unknown");
            if (AgentTraceSanitizer.isInternalKey(key)) {
                continue;
            }
            projected.put(key, AgentTraceSanitizer.isSensitiveKey(key)
                    && isContentValue(entry.getValue())
                    ? "[REDACTED]"
                    : safeSanitizedInputValue(entry.getValue(), 0));
        }
        return Collections.unmodifiableMap(projected);
    }

    private static Object safeSanitizedInputValue(Object value, int depth) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            return AgentTraceSanitizer.snapshotNumber(number);
        }
        if (value instanceof CharSequence || value instanceof Character || value instanceof Enum<?>) {
            return AgentTraceSanitizer.safeSanitizedInputText(String.valueOf(value));
        }
        if (depth >= 3) {
            return "[UNSUPPORTED]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> projected = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (projected.size() >= AgentTraceSanitizer.MAX_COLLECTION_ITEMS) {
                    break;
                }
                String key = eventText(String.valueOf(entry.getKey()), "unknown");
                if (AgentTraceSanitizer.isInternalKey(key)) {
                    continue;
                }
                projected.put(key, AgentTraceSanitizer.isSensitiveKey(key)
                        && isContentValue(entry.getValue())
                        ? "[REDACTED]"
                        : safeSanitizedInputValue(entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(projected);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> projected = new ArrayList<>();
            for (Object item : collection) {
                if (projected.size() >= AgentTraceSanitizer.MAX_COLLECTION_ITEMS) {
                    break;
                }
                projected.add(safeSanitizedInputValue(item, depth + 1));
            }
            return Collections.unmodifiableList(projected);
        }
        return "[UNSUPPORTED]";
    }

    private static Object safeDetailValue(String key, Object value, int depth) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof AgentTraceSanitizer.ContentSnapshot snapshot) {
            return snapshot.toMap();
        }
        if (value instanceof Number number) {
            return AgentTraceSanitizer.snapshotNumber(number);
        }
        if (value instanceof CharSequence || value instanceof Character || value instanceof Enum<?>) {
            int limit = "outputPreview".equals(key)
                    ? AgentTraceSanitizer.MAX_OUTPUT_PREVIEW_CHARS
                    : MAX_EVENT_TEXT_CHARS;
            return boundedEventText(String.valueOf(value), limit);
        }
        if (depth >= 3) {
            return "[UNSUPPORTED]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> projected = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (projected.size() >= AgentTraceSanitizer.MAX_COLLECTION_ITEMS) {
                    break;
                }
                String nestedKey = eventText(String.valueOf(entry.getKey()), "unknown");
                if (AgentTraceSanitizer.isInternalKey(nestedKey)) {
                    continue;
                }
                projected.put(nestedKey, AgentTraceSanitizer.isSensitiveKey(nestedKey)
                                && isContentValue(entry.getValue())
                        ? "[REDACTED]"
                        : safeDetailValue(nestedKey, entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(projected);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> projected = new ArrayList<>();
            for (Object item : collection) {
                if (projected.size() >= AgentTraceSanitizer.MAX_COLLECTION_ITEMS) {
                    break;
                }
                projected.add(safeDetailValue(key, item, depth + 1));
            }
            return Collections.unmodifiableList(projected);
        }
        return "[UNSUPPORTED]";
    }

    private Map<String, Object> safeAdminAttributes(Map<?, ?> source) {
        Map<String, Object> projected = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (projected.size() >= MAX_ADMIN_DIAGNOSTIC_ITEMS) {
                break;
            }
            String key = boundedEventText(String.valueOf(entry.getKey()), MAX_EVENT_TEXT_CHARS);
            if (key.isBlank()) {
                continue;
            }
            projected.put(key, AgentTraceSanitizer.isAdminCredentialKey(key)
                    ? "[REDACTED]"
                    : safeAdminAttributeValue(entry.getValue(), 0));
        }
        return Collections.unmodifiableMap(projected);
    }

    private Object safeAdminAttributeValue(Object value, int depth) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            return AgentTraceSanitizer.snapshotNumber(number);
        }
        if (value instanceof CharSequence || value instanceof Character || value instanceof Enum<?>) {
            return captureContent(String.valueOf(value), MAX_ADMIN_DIAGNOSTIC_TEXT_CHARS).text();
        }
        if (depth >= MAX_ADMIN_DIAGNOSTIC_DEPTH) {
            return "[TRUNCATED_DEPTH]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> projected = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (projected.size() >= MAX_ADMIN_DIAGNOSTIC_ITEMS) {
                    break;
                }
                String key = boundedEventText(String.valueOf(entry.getKey()), MAX_EVENT_TEXT_CHARS);
                if (key.isBlank()) {
                    continue;
                }
                projected.put(key, AgentTraceSanitizer.isAdminCredentialKey(key)
                        ? "[REDACTED]"
                        : safeAdminAttributeValue(entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(projected);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> projected = new ArrayList<>();
            for (Object item : collection) {
                if (projected.size() >= MAX_ADMIN_DIAGNOSTIC_ITEMS) {
                    break;
                }
                projected.add(safeAdminAttributeValue(item, depth + 1));
            }
            return Collections.unmodifiableList(projected);
        }
        return captureContent(String.valueOf(value), MAX_ADMIN_DIAGNOSTIC_TEXT_CHARS).text();
    }

    private synchronized AgentTraceSanitizer.ContentSnapshot captureContent(String value) {
        return captureContent(value, AgentTraceSanitizer.MAX_ADMIN_CONTENT_CHARS);
    }

    private synchronized AgentTraceSanitizer.ContentSnapshot captureContent(String value, int fieldLimit) {
        int remaining = Math.max(
                0,
                AgentTraceSanitizer.MAX_ADMIN_TURN_CAPTURE_CHARS - capturedChars);
        AgentTraceSanitizer.AdminCapture capture = AgentTraceSanitizer.captureAdmin(
                value,
                Math.min(Math.max(0, fieldLimit), remaining));
        AgentTraceSanitizer.ContentSnapshot snapshot = capture.snapshot();
        capturedChars += snapshot.text().length();
        credentialRedactions += capture.redactions();
        if (snapshot.truncated()) {
            truncatedCaptureFields++;
        }
        return snapshot;
    }

    private static boolean isContentValue(Object value) {
        return !(value == null || value instanceof Number || value instanceof Boolean);
    }

    public record TraceStepInfo(int stepIndex, String action, long llmMs, long toolMs) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("stepIndex", stepIndex);
            map.put("action", action);
            map.put("llmMs", llmMs);
            map.put("toolMs", toolMs);
            return map;
        }
    }

    public record TraceToolCallInfo(
            Integer sequence,
            Integer stepIndex,
            String tool,
            String inputSummary,
            String observationSummary,
            long durationMs,
            boolean success,
            String status,
            String errorCode,
            long budgetBeforeMs,
            long budgetAfterMs) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sequence", sequence);
            if (stepIndex != null) {
                map.put("step_index", stepIndex);
            }
            map.put("tool", tool);
            map.put("input_summary", inputSummary);
            map.put("observation_summary", observationSummary);
            map.put("duration_ms", durationMs);
            map.put("success", success);
            map.put("status", status);
            if (errorCode != null && !errorCode.isBlank()) {
                map.put("error_code", errorCode);
            }
            map.put("budget_before_ms", budgetBeforeMs);
            map.put("budget_after_ms", budgetAfterMs);
            return map;
        }
    }

    public record TraceLlmCallInfo(
            Integer sequence,
            Integer stepIndex,
            String purpose,
            String model,
            String status,
            String errorCode,
            int attempt,
            long budgetBeforeMs,
            long budgetAfterMs,
            long durationMs,
            int inputChars,
            int outputChars,
            Long firstTokenMs) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sequence", sequence);
            if (stepIndex != null) {
                map.put("step_index", stepIndex);
            }
            map.put("purpose", purpose);
            map.put("model", model);
            map.put("status", status);
            if (errorCode != null && !errorCode.isBlank()) {
                map.put("error_code", errorCode);
            }
            map.put("attempt", attempt);
            map.put("budget_before_ms", budgetBeforeMs);
            map.put("budget_after_ms", budgetAfterMs);
            map.put("duration_ms", durationMs);
            map.put("input_chars", inputChars);
            map.put("output_chars", outputChars);
            if (firstTokenMs != null) {
                map.put("first_token_ms", firstTokenMs);
            }
            return map;
        }
    }

    /** Raw content passed to or returned by one actual model invocation. */
    public record LlmExchange(
            String systemPrompt,
            String userPrompt,
            String rawOutput,
            String failureClass,
            String failureMessage) {

        public static LlmExchange success(String systemPrompt, String userPrompt, String rawOutput) {
            return new LlmExchange(systemPrompt, userPrompt, rawOutput, "", "");
        }

        public static LlmExchange failure(
                String systemPrompt,
                String userPrompt,
                String rawOutput,
                Throwable failure) {
            StringBuilder classes = new StringBuilder();
            StringBuilder messages = new StringBuilder();
            Throwable current = failure;
            int depth = 0;
            while (current != null && depth++ < 32) {
                if (!classes.isEmpty()) {
                    classes.append(" -> ");
                    messages.append(" -> ");
                }
                classes.append(current.getClass().getName());
                messages.append(current.getMessage() == null ? "" : current.getMessage());
                current = current.getCause();
            }
            return new LlmExchange(
                    systemPrompt,
                    userPrompt,
                    rawOutput,
                    classes.toString(),
                    messages.toString());
        }
    }

    public record TraceEventInfo(
            int sequence,
            String phase,
            String type,
            String name,
            String status,
            long startedOffsetMs,
            long durationMs,
            Integer stepIndex,
            Integer attempt,
            Long budgetBeforeMs,
            Long budgetAfterMs,
            String errorCode,
            Map<String, Object> details) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sequence", sequence);
            map.put("phase", phase);
            map.put("type", type);
            map.put("name", name);
            map.put("status", status);
            map.put("started_offset_ms", startedOffsetMs);
            map.put("duration_ms", durationMs);
            if (stepIndex != null) {
                map.put("step_index", stepIndex);
            }
            if (attempt != null) {
                map.put("attempt", attempt);
            }
            if (budgetBeforeMs != null) {
                map.put("budget_before_ms", budgetBeforeMs);
            }
            if (budgetAfterMs != null) {
                map.put("budget_after_ms", budgetAfterMs);
            }
            if (errorCode != null && !errorCode.isBlank()) {
                map.put("error_code", errorCode);
            }
            if (details != null && !details.isEmpty()) {
                map.put("details", details);
            }
            return map;
        }
    }

    public enum FailureType {
        NONE,
        INVALID_INPUT,
        RETRIEVAL_EMPTY,
        RETRIEVAL_TIMEOUT,
        SKILL_NO_MATCH,
        SKILL_VALIDATION_FAILED,
        TOOL_FAILED,
        LLM_TIMEOUT,
        LLM_INVALID_OUTPUT,
        TURN_TIMEOUT,
        TURN_CANCELLED,
        TURN_COORDINATION_FAILED,
        CLIENT_DELIVERY_FAILED,
        MEMORY_WRITE_FAILED,
        CITATION_INVALID,
        DRAFT_VERSION_CONFLICT,
        PERMISSION_DENIED,
        INTERNAL_ERROR
    }

    public enum OutcomeReason {
        NONE,
        NEEDS_CLARIFICATION,
        NO_EVIDENCE,
        INVALID_CITATION,
        MODEL_FAILURE
    }
}
