package com.chtholly.agent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 单次 Agent 执行的可观测性追踪（轻量，无 OTel）。 */
@Slf4j
@Getter
public class AgentExecutionTrace {

    private final long userId;
    private final String sessionId;
    private final int maxSteps;
    private final long startedAtMs = System.currentTimeMillis();

    private int totalSteps;
    private int llmCalls;
    private long llmDurationMs;
    private long toolDurationMs;
    private long inputTokenEstimate;
    private long outputTokenEstimate;
    private int finalAnswerLength;
    private String terminatedBy = "error";

    private final Set<String> toolsCalled = new LinkedHashSet<>();
    private final List<String> stepActions = new ArrayList<>();

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

    public void recordToolCall(String toolName, long durationMs) {
        if (toolName != null && !toolName.isBlank()) {
            toolsCalled.add(toolName);
        }
        toolDurationMs += durationMs;
    }

    public void recordStep(int stepIndex, String action, long stepLlmMs, long stepToolMs) {
        totalSteps = stepIndex + 1;
        stepActions.add(action);
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

    public void finishAndLog(ObjectMapper objectMapper, AgentMetrics metrics) {
        long totalDurationMs = System.currentTimeMillis() - startedAtMs;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("event", "agent_execution_complete");
        summary.put("userId", userId);
        if (sessionId != null) {
            summary.put("sessionId", sessionId);
        }
        summary.put("totalSteps", totalSteps);
        summary.put("toolsCalled", new ArrayList<>(toolsCalled));
        summary.put("llmCalls", llmCalls);
        summary.put("totalDurationMs", totalDurationMs);
        summary.put("llmDurationMs", llmDurationMs);
        summary.put("toolDurationMs", toolDurationMs);
        summary.put("inputTokens", inputTokenEstimate);
        summary.put("outputTokens", outputTokenEstimate);
        summary.put("finalAnswerLength", finalAnswerLength);
        summary.put("terminatedBy", terminatedBy);

        try {
            log.info("{}", objectMapper.writeValueAsString(summary));
        } catch (Exception e) {
            log.info("agent_execution_complete userId={} sessionId={} terminatedBy={} durationMs={}",
                    userId, sessionId, terminatedBy, totalDurationMs);
        }

        if (metrics != null) {
            metrics.recordExecution(totalDurationMs, llmCalls, toolsCalled, terminatedBy);
        }
    }

    private static long estimateTokens(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return Math.max(1, chars / 4L);
    }
}
