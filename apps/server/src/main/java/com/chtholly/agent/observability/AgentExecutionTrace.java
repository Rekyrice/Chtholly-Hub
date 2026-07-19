package com.chtholly.agent.observability;

import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.trace.TraceStatus;
import com.chtholly.common.tracing.CorrelationIdSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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

    private final String correlationId;
    private final long userId;
    private final String sessionId;
    private final int maxSteps;
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
    private FailureType failureType = FailureType.NONE;

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

    public AgentExecutionTrace(long userId, String sessionId, int maxSteps) {
        this.correlationId = resolveCorrelationId();
        this.userId = userId;
        this.sessionId = sessionId;
        this.maxSteps = maxSteps;
    }

    private static String resolveCorrelationId() {
        String mdcId = MDC.get(CorrelationIdSupport.MDC_CORRELATION_ID);
        if (mdcId != null && !mdcId.isBlank() && !CorrelationIdSupport.DEFAULT_ID.equals(mdcId)) {
            return mdcId.replace("-", "");
        }
        return UUID.randomUUID().toString().replace("-", "");
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
        llmCalls++;
        llmDurationMs += durationMs;
        inputTokenEstimate += estimateTokens(inputChars);
        outputTokenEstimate += estimateTokens(outputChars);
        llmCallDetails.add(new TraceLlmCallInfo(
                nextEventSequence(),
                stepIndex,
                durationMs,
                inputChars,
                outputChars,
                firstTokenMs));
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
        if (toolName != null && !toolName.isBlank()) {
            toolsCalled.add(toolName);
        }
        toolDurationMs += durationMs;
        toolCallDetails.add(new TraceToolCallInfo(
                nextEventSequence(),
                stepIndex,
                toolName,
                fingerprintSummary(inputSummary),
                fingerprintSummary(observation),
                durationMs,
                success));
    }

    public void recordStep(int stepIndex, String action, long stepLlmMs, long stepToolMs) {
        totalSteps = stepIndex + 1;
        stepActions.add(action);
        steps.add(new TraceStepInfo(stepIndex, action, stepLlmMs, stepToolMs));
        log.info("[Agent] Step {}/{}: action={}, llm_ms={}, tool_ms={}",
                stepIndex + 1, maxSteps, action, stepLlmMs, stepToolMs);
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
    }

    public void recordSkillSelection(String status, String id, String version) {
        skillSelectionStatus = safe(status, "NOT_EVALUATED");
        skillId = safe(id, "");
        skillVersion = safe(version, "");
    }

    public void recordSkillValidation(String status) {
        skillValidationStatus = safe(status, "NOT_RUN");
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
            metadata.put("citationId", item.citationId());
            metadata.put("documentId", item.documentId());
            metadata.put("source", item.retrievalSource());
            metadata.put("sourceVersion", item.sourceVersion());
            metadata.put("sourceHash", item.sourceHash());
            return Map.copyOf(metadata);
        }).toList();
    }

    public void recordCitationValidation(String status) {
        citationValidationStatus = safe(status, "NOT_RUN");
    }

    public void recordTools(Set<String> toolNames) {
        TreeMap<String, String> versions = new TreeMap<>();
        if (toolNames != null) {
            toolNames.stream()
                    .filter(name -> name != null && !name.isBlank())
                    .forEach(name -> versions.put(name, AgentComponentVersions.TOOLS));
        }
        toolVersions = Map.copyOf(versions);
    }

    public void markFailure(FailureType type) {
        failureType = type == null ? FailureType.INTERNAL_ERROR : type;
    }

    public void terminateFinalAnswer(String answer) {
        terminatedBy = "final_answer";
        finalAnswerLength = answer == null ? 0 : answer.length();
    }

    public void terminateMaxSteps() {
        terminatedBy = "max_steps";
    }

    public void terminateTimeout() {
        terminatedBy = "timeout";
    }

    public void terminateError() {
        terminatedBy = "error";
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
        payload.put("errorMessage", errorMessage);
        payload.put("startedAt", startedAt.toString());
        if (finishedAtMs != null) {
            payload.put("finishedAt", Instant.ofEpochMilli(finishedAtMs).toString());
        }
        payload.put("steps", steps.stream().map(TraceStepInfo::toMap).toList());
        payload.put("toolCalls", toolCallDetails.stream().map(TraceToolCallInfo::toMap).toList());
        payload.put("llmCalls", llmCallDetails.stream().map(TraceLlmCallInfo::toMap).toList());
        payload.put("components", componentVersions());
        payload.put("skill", skillMetadata());
        payload.put("retrieval", retrievalMetadata());
        payload.put("toolVersions", toolVersions);
        payload.put("failureType", failureType.name());
        payload.put("runMode", runMode);
        payload.put("input", inputMetadata());
        return payload;
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
            case "max_steps" -> TraceStatus.ABORTED;
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
        return value == null || value.isBlank() ? fallback : value.strip();
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
            boolean success) {
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
            return map;
        }
    }

    public record TraceLlmCallInfo(
            Integer sequence,
            Integer stepIndex,
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
            map.put("duration_ms", durationMs);
            map.put("input_chars", inputChars);
            map.put("output_chars", outputChars);
            if (firstTokenMs != null) {
                map.put("first_token_ms", firstTokenMs);
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
        CITATION_INVALID,
        DRAFT_VERSION_CONFLICT,
        PERMISSION_DENIED,
        INTERNAL_ERROR
    }
}
