package com.chtholly.agent.observability;

import com.chtholly.agent.trace.TraceStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 单次 Agent 执行的可观测性追踪（轻量，无 OTel）。 */
@Slf4j
@Getter
public class AgentExecutionTrace {

    private final String correlationId = UUID.randomUUID().toString().replace("-", "");
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
    private String terminatedBy = "error";

    @Setter
    private String errorMessage;

    private Long finishedAtMs;
    private Long durationMs;
    private TraceStatus status;

    private final Set<String> toolsCalled = new LinkedHashSet<>();
    private final List<String> stepActions = new ArrayList<>();
    private final List<TraceStepInfo> steps = new ArrayList<>();
    private final List<TraceToolCallInfo> toolCallDetails = new ArrayList<>();

    public AgentExecutionTrace(long userId, String sessionId, int maxSteps) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.maxSteps = maxSteps;
    }

    public void recordLlmCall(long durationMs, int inputChars, int outputChars) {
        llmCalls++;
        llmDurationMs += durationMs;
        inputTokenEstimate += estimateTokens(inputChars);
        outputTokenEstimate += estimateTokens(outputChars);
    }

    public void recordToolCall(String toolName, long durationMs, String inputSummary, String observation) {
        if (toolName != null && !toolName.isBlank()) {
            toolsCalled.add(toolName);
        }
        toolDurationMs += durationMs;
        boolean success = !isFailureObservation(observation);
        toolCallDetails.add(new TraceToolCallInfo(
                toolName,
                truncate(inputSummary, 256),
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
        Map<String, Object> summary = buildSummaryMap();
        summary.put("correlationId", correlationId);
        summary.put("status", status == null ? null : status.name());

        try {
            log.info("{}", objectMapper.writeValueAsString(summary));
        } catch (Exception e) {
            log.info("agent_execution_complete correlationId={} userId={} sessionId={} terminatedBy={} durationMs={}",
                    correlationId, userId, sessionId, terminatedBy, durationMs);
        }

        if (metrics != null) {
            metrics.recordExecution(durationMs == null ? 0 : durationMs, llmCalls, toolsCalled, terminatedBy);
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
        return payload;
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
        summary.put("llmCalls", llmCalls);
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

    private static boolean isFailureObservation(String observation) {
        if (observation == null || observation.isBlank()) {
            return false;
        }
        String lower = observation.toLowerCase();
        return lower.contains("timed out")
                || lower.contains("timeout")
                || lower.contains("失败")
                || lower.contains("无法")
                || lower.contains("错误");
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
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

    public record TraceToolCallInfo(String tool, String inputSummary, long durationMs, boolean success) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("tool", tool);
            map.put("input_summary", inputSummary);
            map.put("duration_ms", durationMs);
            map.put("success", success);
            return map;
        }
    }
}
