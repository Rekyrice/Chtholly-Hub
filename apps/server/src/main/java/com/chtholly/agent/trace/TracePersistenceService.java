package com.chtholly.agent.trace;

import com.chtholly.agent.observability.AgentExecutionTrace;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Agent 执行 trace 异步持久化与失败模式挖掘。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TracePersistenceService {

    private static final int MINING_BATCH_SIZE = 200;
    private static final int MAX_SAMPLE_TRACES = 5;

    private final TraceMapper traceMapper;
    private final FailurePatternMapper failurePatternMapper;
    private final ObjectMapper objectMapper;

    /**
     * 异步持久化一次 Agent 执行 trace。
     */
    @Async("traceExecutor")
    public void persist(AgentExecutionTrace trace) {
        if (trace == null || trace.getStatus() == null) {
            return;
        }
        try {
            ExecutionTraceRow row = toRow(trace);
            traceMapper.insert(row);
        } catch (Exception e) {
            log.warn("持久化 Agent trace 失败 correlationId={}: {}", trace.getCorrelationId(), e.getMessage(), e);
        }
    }

    /**
     * 定时任务：从近期失败 trace 中挖掘失败模式，每 6 小时执行一次。
     */
    @Scheduled(fixedDelay = 21_600_000L, initialDelay = 600_000L)
    public void mineFailurePatterns() {
        List<ExecutionTraceRow> failures = traceMapper.findUnanalyzedByStatus(
                TraceStatus.FAILURE.name(), MINING_BATCH_SIZE);
        if (failures.isEmpty()) {
            return;
        }

        Map<String, List<ExecutionTraceRow>> grouped = failures.stream()
                .collect(Collectors.groupingBy(this::extractPatternKey));

        Instant now = Instant.now();
        for (var entry : grouped.entrySet()) {
            upsertPattern(entry.getKey(), entry.getValue(), now);
        }

        List<Long> ids = failures.stream().map(ExecutionTraceRow::getId).toList();
        if (!ids.isEmpty()) {
            traceMapper.markPatternAnalyzed(ids);
        }
        log.info("失败模式挖掘完成：处理 {} 条 FAILURE trace，聚合为 {} 个模式",
                failures.size(), grouped.size());
    }

    private ExecutionTraceRow toRow(AgentExecutionTrace trace) throws JsonProcessingException {
        ExecutionTraceRow row = new ExecutionTraceRow();
        row.setCorrelationId(trace.getCorrelationId());
        row.setUserId(trace.getUserId());
        row.setSessionId(trace.getSessionId());
        row.setStartedAt(trace.getStartedAt());
        row.setFinishedAt(trace.getFinishedAt());
        row.setDurationMs(trace.getDurationMs() == null ? null : trace.getDurationMs().intValue());
        row.setStatus(trace.getStatus().name());
        row.setStepsCount(trace.getSteps().size());
        row.setToolCalls(objectMapper.writeValueAsString(
                trace.getToolCallDetails().stream().map(AgentExecutionTrace.TraceToolCallInfo::toMap).toList()));
        row.setErrorMessage(trace.getErrorMessage());
        row.setInputTokens(safeInt(trace.getInputTokenEstimate()));
        row.setOutputTokens(safeInt(trace.getOutputTokenEstimate()));
        row.setTracePayload(objectMapper.writeValueAsString(trace.toPayloadMap()));
        return row;
    }

    private void upsertPattern(String patternKey, List<ExecutionTraceRow> traces, Instant now) {
        List<String> sampleIds = traces.stream()
                .map(ExecutionTraceRow::getCorrelationId)
                .distinct()
                .limit(MAX_SAMPLE_TRACES)
                .toList();
        String sampleJson = writeJson(sampleIds);

        TraceFailurePatternRow existing = failurePatternMapper.findByPatternKey(patternKey);
        if (existing == null) {
            TraceFailurePatternRow row = new TraceFailurePatternRow();
            row.setPatternKey(patternKey);
            row.setOccurrenceCount(traces.size());
            row.setLastSeenAt(now);
            row.setSampleTraceIds(sampleJson);
            row.setResolutionHint(defaultResolutionHint(patternKey));
            failurePatternMapper.insert(row);
            return;
        }

        Set<String> merged = new LinkedHashSet<>(readSampleIds(existing.getSampleTraceIds()));
        merged.addAll(sampleIds);
        List<String> capped = merged.stream().limit(MAX_SAMPLE_TRACES).toList();
        failurePatternMapper.updatePattern(patternKey, traces.size(), now, writeJson(capped));
    }

    /**
     * 从失败 trace 提取模式键，格式：{category}:{detail}:{failure_type}
     */
    String extractPatternKey(ExecutionTraceRow trace) {
        String fixedFailure = fixedFailureType(trace.getTracePayload());
        if (fixedFailure != null) {
            return "failure:" + fixedFailure.toLowerCase(java.util.Locale.ROOT);
        }
        String error = trace.getErrorMessage() == null ? "" : trace.getErrorMessage().toLowerCase();
        if (error.contains("timeout") || error.contains("超时")) {
            return "execution:llm:timeout";
        }

        List<Map<String, Object>> toolCalls = readToolCalls(trace.getToolCalls());
        for (Map<String, Object> call : toolCalls) {
            Object success = call.get("success");
            if (Boolean.FALSE.equals(success)) {
                String tool = boundedToolName(call.get("tool"));
                long durationMs = toLong(call.get("duration_ms"));
                if (durationMs >= 5_000 || String.valueOf(call.get("input_summary")).contains("timeout")) {
                    return "tool:" + tool + ":timeout";
                }
                return "tool:" + tool + ":failure";
            }
        }

        String payload = trace.getTracePayload() == null ? "" : trace.getTracePayload();
        if (payload.contains("max_steps") || payload.contains("\"terminatedBy\":\"max_steps\"")) {
            return "step:limit:exceeded";
        }
        if (payload.contains("parse_error")) {
            return "step:parse:error";
        }
        return "execution:unknown:failure";
    }

    private String fixedFailureType(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            String value = objectMapper.readTree(payload).path("failureType").asText("");
            AgentExecutionTrace.FailureType type = AgentExecutionTrace.FailureType.valueOf(value);
            return type == AgentExecutionTrace.FailureType.NONE ? null : type.name();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String boundedToolName(Object value) {
        String tool = value == null ? "" : String.valueOf(value);
        return tool.matches("[a-z0-9_]{1,64}") ? tool : "unknown";
    }

    private String defaultResolutionHint(String patternKey) {
        if (patternKey.startsWith("tool:") && patternKey.endsWith(":timeout")) {
            return "检查工具超时配置或外部 API 可用性，必要时缩短 keyword 并重试。";
        }
        if ("step:limit:exceeded".equals(patternKey)) {
            return "引导用户简化问题，或适当提高 agent.max-steps。";
        }
        if ("execution:llm:timeout".equals(patternKey)) {
            return "检查 LLM 服务延迟与 agent.llm-timeout-seconds 配置。";
        }
        return null;
    }

    private List<Map<String, Object>> readToolCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> readSampleIds(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static int safeInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) value;
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
