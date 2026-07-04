package com.chtholly.agent.trace;

import com.chtholly.agent.trace.dto.FailurePatternDto;
import com.chtholly.agent.trace.dto.TraceDetailDto;
import com.chtholly.agent.trace.dto.TraceStatsDto;
import com.chtholly.agent.trace.dto.TraceSummaryDto;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TraceQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final TraceMapper traceMapper;
    private final FailurePatternMapper failurePatternMapper;
    private final ObjectMapper objectMapper;

    public PageResponse<TraceSummaryDto> listTraces(int page, int size, String status, Long userId) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int offset = safePage * safeSize;

        List<TraceSummaryDto> items = traceMapper.list(status, userId, safeSize, offset).stream()
                .map(TraceSummaryDto::from)
                .toList();
        long total = traceMapper.count(status, userId);
        boolean hasMore = (long) (safePage + 1) * safeSize < total;
        return PageResponse.offset(items, safePage, safeSize, total, hasMore);
    }

    public TraceDetailDto getTrace(String correlationId) {
        ExecutionTraceRow row = traceMapper.findByCorrelationId(correlationId);
        if (row == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Trace 不存在");
        }
        return TraceDetailDto.from(row, parseJson(row.getToolCalls()), parseJson(row.getTracePayload()));
    }

    public TraceStatsDto getStats(int days) {
        int safeDays = Math.min(Math.max(1, days), 90);
        Instant since = Instant.now().minus(safeDays, ChronoUnit.DAYS);

        long total = traceMapper.countSince(since);
        long success = traceMapper.countByStatusSince(TraceStatus.SUCCESS.name(), since);
        long failure = traceMapper.countByStatusSince(TraceStatus.FAILURE.name(), since);
        long timeout = traceMapper.countByStatusSince(TraceStatus.TIMEOUT.name(), since);
        long aborted = traceMapper.countByStatusSince(TraceStatus.ABORTED.name(), since);
        double successRate = total == 0 ? 0.0 : (success * 100.0 / total);
        Double avgDuration = traceMapper.avgDurationSince(since);
        Integer p95 = computeP95(traceMapper.listDurationsSince(since, 5000));

        List<FailurePatternDto> topPatterns = failurePatternMapper.listAllOrderByCountDesc(5).stream()
                .map(row -> FailurePatternDto.from(row, parseJson(row.getSampleTraceIds())))
                .toList();

        List<TraceStatsDto.TokenTrendPoint> trend = traceMapper.tokenTrendSince(since).stream()
                .map(row -> new TraceStatsDto.TokenTrendPoint(
                        row.getDay(),
                        row.getInputTokens() == null ? 0L : row.getInputTokens(),
                        row.getOutputTokens() == null ? 0L : row.getOutputTokens()))
                .toList();

        return new TraceStatsDto(
                safeDays,
                total,
                success,
                failure,
                timeout,
                aborted,
                successRate,
                avgDuration,
                p95,
                topPatterns,
                trend
        );
    }

    public List<FailurePatternDto> getFailurePatterns() {
        return failurePatternMapper.listAllOrderByCountDesc(100).stream()
                .map(row -> FailurePatternDto.from(row, parseJson(row.getSampleTraceIds())))
                .toList();
    }

    private Integer computeP95(List<Integer> durations) {
        if (durations == null || durations.isEmpty()) {
            return null;
        }
        List<Integer> sorted = new ArrayList<>(durations);
        Collections.sort(sorted);
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode().put("raw", json);
        }
    }
}
