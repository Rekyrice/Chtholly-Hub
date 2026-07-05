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
    private static final int MAX_STATS_DAYS = 90;
    private static final int P95_SAMPLE_LIMIT = 5000;

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
        int safeDays = Math.min(Math.max(1, days), MAX_STATS_DAYS);
        Instant since = Instant.now().minus(safeDays, ChronoUnit.DAYS);

        long total = traceMapper.countSince(since);
        long success = traceMapper.countByStatusSince(TraceStatus.SUCCESS.name(), since);
        long failure = traceMapper.countByStatusSince(TraceStatus.FAILURE.name(), since);
        long timeout = traceMapper.countByStatusSince(TraceStatus.TIMEOUT.name(), since);
        long aborted = traceMapper.countByStatusSince(TraceStatus.ABORTED.name(), since);
        double successRate = total == 0 ? 0.0 : (success * 100.0 / total);
        Double avgDuration = traceMapper.avgDurationSince(since);
        Integer p95 = computeP95(traceMapper.listDurationsSince(since, P95_SAMPLE_LIMIT));

        List<FailurePatternDto> topPatterns = failurePatternMapper.listAllOrderByCountDesc(5).stream()
                .map(row -> FailurePatternDto.from(row, parseJson(row.getSampleTraceIds())))
                .toList();

        List<TraceStatsDto.TokenTrendPoint> trend = mapTokenTrend(traceMapper.tokenTrendSince(since));

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
                trend,
                List.of()
        );
    }

    public TraceStatsDto getStats(Instant from, Instant to) {
        Range range = normalizeRange(from, to);

        long total = traceMapper.countBetween(range.from(), range.to());
        long success = traceMapper.countByStatusBetween(TraceStatus.SUCCESS.name(), range.from(), range.to());
        long failure = traceMapper.countByStatusBetween(TraceStatus.FAILURE.name(), range.from(), range.to());
        long timeout = traceMapper.countByStatusBetween(TraceStatus.TIMEOUT.name(), range.from(), range.to());
        long aborted = traceMapper.countByStatusBetween(TraceStatus.ABORTED.name(), range.from(), range.to());
        double successRate = total == 0 ? 0.0 : (success * 100.0 / total);
        Double avgDuration = traceMapper.avgDurationBetween(range.from(), range.to());
        Integer p95 = computeP95(traceMapper.listDurationsBetween(range.from(), range.to(), P95_SAMPLE_LIMIT));

        List<FailurePatternDto> topPatterns = failurePatternMapper
                .listBetweenOrderByCountDesc(range.from(), range.to(), 5)
                .stream()
                .map(row -> FailurePatternDto.from(row, parseJson(row.getSampleTraceIds())))
                .toList();

        return new TraceStatsDto(
                range.days(),
                total,
                success,
                failure,
                timeout,
                aborted,
                successRate,
                avgDuration,
                p95,
                topPatterns,
                mapTokenTrend(traceMapper.tokenTrendBetween(range.from(), range.to())),
                mapExecutionTrend(traceMapper.executionTrendBetween(range.from(), range.to()))
        );
    }

    public List<FailurePatternDto> getFailurePatterns() {
        return failurePatternMapper.listAllOrderByCountDesc(100).stream()
                .map(row -> FailurePatternDto.from(row, parseJson(row.getSampleTraceIds())))
                .toList();
    }

    public List<FailurePatternDto> getFailurePatterns(Instant from, Instant to) {
        Range range = normalizeRange(from, to);
        return failurePatternMapper.listBetweenOrderByCountDesc(range.from(), range.to(), 100).stream()
                .map(row -> FailurePatternDto.from(row, parseJson(row.getSampleTraceIds())))
                .toList();
    }

    public List<TraceStatsDto.TokenTrendPoint> getTokenTrends(Instant from, Instant to) {
        Range range = normalizeRange(from, to);
        return mapTokenTrend(traceMapper.tokenTrendBetween(range.from(), range.to()));
    }

    private List<TraceStatsDto.TokenTrendPoint> mapTokenTrend(List<TraceTokenTrendRow> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(row -> new TraceStatsDto.TokenTrendPoint(
                        row.getDay(),
                        row.getInputTokens() == null ? 0L : row.getInputTokens(),
                        row.getOutputTokens() == null ? 0L : row.getOutputTokens()))
                .toList();
    }

    private List<TraceStatsDto.ExecutionTrendPoint> mapExecutionTrend(List<TraceDailyTrendRow> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(row -> {
                    long total = row.getTotalExecutions() == null ? 0L : row.getTotalExecutions();
                    long success = row.getSuccessCount() == null ? 0L : row.getSuccessCount();
                    double successRate = total == 0 ? 0.0 : (success * 100.0 / total);
                    return new TraceStatsDto.ExecutionTrendPoint(row.getDay(), total, success, successRate);
                })
                .toList();
    }

    private Range normalizeRange(Instant from, Instant to) {
        Instant safeTo = to == null ? Instant.now() : to;
        Instant safeFrom = from == null ? safeTo.minus(7, ChronoUnit.DAYS) : from;
        if (!safeFrom.isBefore(safeTo)) {
            safeFrom = safeTo.minus(1, ChronoUnit.DAYS);
        }
        long days = ChronoUnit.DAYS.between(safeFrom, safeTo);
        int safeDays = (int) Math.min(Math.max(1L, days), MAX_STATS_DAYS);
        return new Range(safeFrom, safeTo, safeDays);
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

    private record Range(Instant from, Instant to, int days) {}
}
