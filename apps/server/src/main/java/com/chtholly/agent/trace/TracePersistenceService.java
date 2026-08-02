package com.chtholly.agent.trace;

import com.chtholly.agent.observability.AgentExecutionTrace;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Locale;
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
            trace.requireClientDeliveryResolved();
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
        List<ExecutionTraceRow> candidates = traceMapper.findUnanalyzedFailureCandidates(MINING_BATCH_SIZE);
        if (candidates.isEmpty()) {
            return;
        }

        Map<String, List<ExecutionTraceRow>> grouped = candidates.stream()
                .collect(Collectors.groupingBy(this::extractPatternKey));

        Instant now = Instant.now();
        for (var entry : grouped.entrySet()) {
            upsertPattern(entry.getKey(), entry.getValue(), now);
        }

        List<Long> ids = candidates.stream().map(ExecutionTraceRow::getId).toList();
        if (!ids.isEmpty()) {
            traceMapper.markPatternAnalyzed(ids);
        }
        log.info("失败模式挖掘完成：处理 {} 条失败候选 trace，聚合为 {} 个模式",
                candidates.size(), grouped.size());
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
        JsonNode payload = readTracePayload(trace.getTracePayload());
        if ("max_steps".equals(payload.path("terminatedBy").asText())) {
            return "step:limit:exceeded";
        }

        String fixedFailure = fixedFailureType(payload);
        if (fixedFailure != null) {
            return "failure:" + fixedFailure.toLowerCase(Locale.ROOT);
        }

        List<Map<String, Object>> toolCalls = readToolCalls(trace.getToolCalls());
        String structuredToolFailure = structuredToolFailure(toolCalls);
        if (structuredToolFailure != null) {
            return structuredToolFailure;
        }

        String error = trace.getErrorMessage() == null
                ? ""
                : trace.getErrorMessage().toLowerCase(Locale.ROOT);
        if (error.contains("timeout") || error.contains("超时")) {
            return "execution:llm:timeout";
        }

        for (Map<String, Object> call : toolCalls) {
            if (call == null || hasStructuredOutcome(call)) {
                continue;
            }
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

        if (hasParseError(payload)) {
            return "step:parse:error";
        }
        return "execution:unknown:failure";
    }

    private String structuredToolFailure(List<Map<String, Object>> toolCalls) {
        for (Map<String, Object> call : toolCalls) {
            if (call == null || !hasStructuredOutcome(call)) {
                continue;
            }
            String status = boundedToolStatus(call.get("status"));
            if ("SUCCESS".equals(status)) {
                continue;
            }
            String tool = boundedToolName(call.get("tool"));
            String errorCode = boundedErrorCode(call.get("error_code"));
            if ("TIMEOUT".equals(status) || "TOOL_TIMEOUT".equals(errorCode)) {
                return "tool:" + tool + ":timeout";
            }
            if (!errorCode.isEmpty()) {
                return "tool:" + tool + ":" + errorCode.toLowerCase(Locale.ROOT);
            }
            String failure = switch (status) {
                case "VALIDATION_ERROR" -> "validation_error";
                case "INTERRUPTED" -> "interrupted";
                default -> "failure";
            };
            return "tool:" + tool + ":" + failure;
        }
        return null;
    }

    private boolean hasStructuredOutcome(Map<String, Object> call) {
        return call.containsKey("status") || call.containsKey("error_code");
    }

    private String fixedFailureType(JsonNode payload) {
        String value = payload.path("failureType").asText("");
        for (AgentExecutionTrace.FailureType type : AgentExecutionTrace.FailureType.values()) {
            if (type.name().equals(value)) {
                return type == AgentExecutionTrace.FailureType.NONE ? null : type.name();
            }
        }
        return null;
    }

    private JsonNode readTracePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(payload);
            return parsed == null ? objectMapper.createObjectNode() : parsed;
        } catch (JsonProcessingException e) {
            log.debug("解析 Agent trace payload 失败，按空 payload 处理", e);
            return objectMapper.createObjectNode();
        }
    }

    private String boundedToolName(Object value) {
        String tool = value == null ? "" : String.valueOf(value);
        return tool.matches("[a-z0-9_]{1,64}") ? tool : "unknown";
    }

    private String boundedToolStatus(Object value) {
        String status = value == null ? "" : String.valueOf(value);
        return switch (status) {
            case "SUCCESS", "VALIDATION_ERROR", "TIMEOUT", "ERROR", "INTERRUPTED" -> status;
            default -> "";
        };
    }

    private String boundedErrorCode(Object value) {
        String errorCode = value == null ? "" : String.valueOf(value);
        return errorCode.matches("[A-Z0-9_]{1,64}") ? errorCode : "";
    }

    private boolean hasParseError(JsonNode payload) {
        for (JsonNode step : payload.path("steps")) {
            if ("parse_error".equals(step.path("action").asText())) {
                return true;
            }
        }
        return false;
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
            List<Map<String, Object>> toolCalls = objectMapper.readValue(json, new TypeReference<>() {});
            return toolCalls == null ? List.of() : toolCalls;
        } catch (Exception e) {
            log.debug("解析 Agent tool calls 失败，按空列表处理", e);
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
