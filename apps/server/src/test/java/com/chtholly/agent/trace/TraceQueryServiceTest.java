package com.chtholly.agent.trace;

import com.chtholly.agent.trace.dto.TraceStatsDto;
import com.chtholly.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceQueryServiceTest {

    @Mock
    private TraceMapper traceMapper;
    @Mock
    private FailurePatternMapper failurePatternMapper;

    private TraceQueryService service;

    @BeforeEach
    void setUp() {
        service = new TraceQueryService(traceMapper, failurePatternMapper, new ObjectMapper());
    }

    @Test
    void getStatsAggregatesCountsAndP95() {
        when(traceMapper.countSince(any())).thenReturn(10L);
        when(traceMapper.countByStatusSince(eq(TraceStatus.SUCCESS.name()), any())).thenReturn(8L);
        when(traceMapper.countByStatusSince(eq(TraceStatus.FAILURE.name()), any())).thenReturn(1L);
        when(traceMapper.countByStatusSince(eq(TraceStatus.TIMEOUT.name()), any())).thenReturn(1L);
        when(traceMapper.countByStatusSince(eq(TraceStatus.ABORTED.name()), any())).thenReturn(0L);
        when(traceMapper.avgDurationSince(any())).thenReturn(2300.0);
        when(traceMapper.listDurationsSince(any(), anyInt())).thenReturn(List.of(100, 200, 300, 400, 5000));
        when(failurePatternMapper.listAllOrderByCountDesc(5)).thenReturn(List.of());
        TraceTokenTrendRow trend = new TraceTokenTrendRow();
        trend.setDay(LocalDate.now());
        trend.setInputTokens(100L);
        trend.setOutputTokens(50L);
        when(traceMapper.tokenTrendSince(any())).thenReturn(List.of(trend));

        TraceStatsDto stats = service.getStats(7);

        assertThat(stats.totalExecutions()).isEqualTo(10);
        assertThat(stats.successCount()).isEqualTo(8);
        assertThat(stats.successRate()).isEqualTo(80.0);
        assertThat(stats.avgDurationMs()).isEqualTo(2300.0);
        assertThat(stats.p95DurationMs()).isEqualTo(5000);
        assertThat(stats.tokenTrend()).hasSize(1);
    }

    @Test
    void getStatsBetweenAggregatesCountsAndP95WithinRange() {
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-09T00:00:00Z");
        when(traceMapper.countBetween(from, to)).thenReturn(4L);
        when(traceMapper.countByStatusBetween(TraceStatus.SUCCESS.name(), from, to)).thenReturn(3L);
        when(traceMapper.countByStatusBetween(TraceStatus.FAILURE.name(), from, to)).thenReturn(1L);
        when(traceMapper.countByStatusBetween(TraceStatus.TIMEOUT.name(), from, to)).thenReturn(0L);
        when(traceMapper.countByStatusBetween(TraceStatus.ABORTED.name(), from, to)).thenReturn(0L);
        when(traceMapper.avgDurationBetween(from, to)).thenReturn(1200.0);
        when(traceMapper.listDurationsBetween(from, to, 5000)).thenReturn(List.of(100, 200, 1000, 5000));
        when(failurePatternMapper.listBetweenOrderByCountDesc(from, to, 5)).thenReturn(List.of());
        when(traceMapper.tokenTrendBetween(from, to)).thenReturn(List.of());

        TraceStatsDto stats = service.getStats(from, to);

        assertThat(stats.totalExecutions()).isEqualTo(4);
        assertThat(stats.successRate()).isEqualTo(75.0);
        assertThat(stats.avgDurationMs()).isEqualTo(1200.0);
        assertThat(stats.p95DurationMs()).isEqualTo(5000);
        verify(traceMapper).countBetween(from, to);
    }

    @Test
    void getFailurePatternsBetweenUsesLastSeenRange() {
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-09T00:00:00Z");
        when(failurePatternMapper.listBetweenOrderByCountDesc(from, to, 100)).thenReturn(List.of());

        assertThat(service.getFailurePatterns(from, to)).isEmpty();

        verify(failurePatternMapper).listBetweenOrderByCountDesc(from, to, 100);
    }

    @Test
    void getTokenTrendsBetweenMapsRowsToDtoPoints() {
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-09T00:00:00Z");
        TraceTokenTrendRow row = new TraceTokenTrendRow();
        row.setDay(LocalDate.parse("2026-07-02"));
        row.setInputTokens(300L);
        row.setOutputTokens(120L);
        when(traceMapper.tokenTrendBetween(from, to)).thenReturn(List.of(row));

        List<TraceStatsDto.TokenTrendPoint> points = service.getTokenTrends(from, to);

        assertThat(points).singleElement()
                .satisfies(point -> {
                    assertThat(point.day()).isEqualTo(LocalDate.parse("2026-07-02"));
                    assertThat(point.inputTokens()).isEqualTo(300L);
                    assertThat(point.outputTokens()).isEqualTo(120L);
                });
    }

    @Test
    void getTraceThrowsWhenMissing() {
        when(traceMapper.findByCorrelationId("missing")).thenReturn(null);
        assertThatThrownBy(() -> service.getTrace("missing"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getTraceReturnsDetail() {
        ExecutionTraceRow row = new ExecutionTraceRow();
        row.setCorrelationId("abc");
        row.setStatus(TraceStatus.SUCCESS.name());
        row.setToolCalls("[]");
        row.setTracePayload("{\"event\":\"agent_execution_complete\"}");
        when(traceMapper.findByCorrelationId("abc")).thenReturn(row);

        var detail = service.getTrace("abc");
        assertThat(detail.correlationId()).isEqualTo("abc");
        assertThat(detail.tracePayload().path("event").asText()).isEqualTo("agent_execution_complete");
    }
}
