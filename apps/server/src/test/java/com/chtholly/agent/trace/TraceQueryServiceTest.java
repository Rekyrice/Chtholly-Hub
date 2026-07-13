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

    @Test
    void getTraceBuildsTypedStepsFromExplicitAssociations() {
        ExecutionTraceRow row = new ExecutionTraceRow();
        row.setCorrelationId("hierarchical");
        row.setStatus(TraceStatus.SUCCESS.name());
        row.setToolCalls("[]");
        row.setTracePayload("""
                {
                  "steps":[{"stepIndex":0,"action":"fulltext_search","llmMs":40,"toolMs":80}],
                  "llmCalls":[{"sequence":1,"step_index":0,"duration_ms":40,"input_chars":120,"output_chars":32}],
                  "toolCalls":[{"sequence":2,"step_index":0,"tool":"fulltext_search","duration_ms":80,"success":true,"input_summary":"{}","observation_summary":"found 3 posts"}]
                }
                """);
        when(traceMapper.findByCorrelationId("hierarchical")).thenReturn(row);

        var detail = service.getTrace("hierarchical");

        assertThat(detail.steps()).singleElement().satisfies(step -> {
            assertThat(step.stepIndex()).isZero();
            assertThat(step.action()).isEqualTo("fulltext_search");
            assertThat(step.events()).extracting(event -> event.type())
                    .containsExactly("llm", "tool");
            assertThat(step.events()).extracting(event -> event.sequence())
                    .containsExactly(1, 2);
        });
        assertThat(detail.unassignedEvents()).isEmpty();
    }

    @Test
    void getTraceKeepsLegacyEventsUnassignedWithoutGuessing() {
        ExecutionTraceRow row = new ExecutionTraceRow();
        row.setCorrelationId("legacy");
        row.setStatus(TraceStatus.FAILURE.name());
        row.setToolCalls("[{\"tool\":\"search\",\"duration_ms\":12,\"success\":false}]");
        row.setTracePayload("""
                {
                  "steps":[{"stepIndex":0,"action":"search","llmMs":8,"toolMs":12}],
                  "llmCalls":[{"duration_ms":8,"input_chars":20,"output_chars":10}]
                }
                """);
        when(traceMapper.findByCorrelationId("legacy")).thenReturn(row);

        var detail = service.getTrace("legacy");

        assertThat(detail.steps()).singleElement()
                .satisfies(step -> assertThat(step.events()).isEmpty());
        assertThat(detail.unassignedEvents()).hasSize(2)
                .allSatisfy(event -> {
                    assertThat(event.stepIndex()).isNull();
                    assertThat(event.sequence()).isNull();
                });
    }
}
