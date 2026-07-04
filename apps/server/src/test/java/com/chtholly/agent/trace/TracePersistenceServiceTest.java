package com.chtholly.agent.trace;

import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.trace.TraceStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TracePersistenceServiceTest {

    @Mock
    private TraceMapper traceMapper;
    @Mock
    private FailurePatternMapper failurePatternMapper;

    private TracePersistenceService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new TracePersistenceService(traceMapper, failurePatternMapper, objectMapper);
    }

    @Test
    void persistWritesExecutionTraceRow() {
        AgentExecutionTrace trace = new AgentExecutionTrace(7L, "sess-1", 5);
        trace.recordToolCall("bangumi_search", 120, "{\"keyword\":\"re0\"}", "ok");
        trace.recordStep(0, "bangumi_search", 80, 120);
        trace.terminateFinalAnswer("answer");
        trace.finish();

        service.persist(trace);

        ArgumentCaptor<ExecutionTraceRow> captor = ArgumentCaptor.forClass(ExecutionTraceRow.class);
        verify(traceMapper).insert(captor.capture());
        ExecutionTraceRow row = captor.getValue();
        assertThat(row.getCorrelationId()).isEqualTo(trace.getCorrelationId());
        assertThat(row.getUserId()).isEqualTo(7L);
        assertThat(row.getSessionId()).isEqualTo("sess-1");
        assertThat(row.getStatus()).isEqualTo(TraceStatus.SUCCESS.name());
        assertThat(row.getStepsCount()).isEqualTo(1);
        assertThat(row.getTracePayload()).contains("agent_execution_complete");
    }

    @Test
    void mineFailurePatternsGroupsAndMarksAnalyzed() throws Exception {
        ExecutionTraceRow failure = new ExecutionTraceRow();
        failure.setId(10L);
        failure.setCorrelationId("abc123");
        failure.setToolCalls(objectMapper.writeValueAsString(List.of(
                java.util.Map.of("tool", "bangumi_search", "success", false, "duration_ms", 6000, "input_summary", "re0")
        )));
        failure.setTracePayload("{\"terminatedBy\":\"error\"}");

        when(traceMapper.findUnanalyzedByStatus(eq(TraceStatus.FAILURE.name()), anyInt()))
                .thenReturn(List.of(failure));
        when(failurePatternMapper.findByPatternKey(anyString())).thenReturn(null);

        service.mineFailurePatterns();

        verify(failurePatternMapper).insert(any(TraceFailurePatternRow.class));
        verify(traceMapper).markPatternAnalyzed(List.of(10L));
    }

    @Test
    void extractPatternKeyDetectsToolTimeout() throws Exception {
        ExecutionTraceRow row = new ExecutionTraceRow();
        row.setToolCalls(objectMapper.writeValueAsString(List.of(
                java.util.Map.of("tool", "bangumi_search", "success", false, "duration_ms", 6000, "input_summary", "re0")
        )));
        assertThat(service.extractPatternKey(row)).isEqualTo("tool:bangumi_search:timeout");
    }

    @Test
    void extractPatternKeyDetectsStepLimit() {
        ExecutionTraceRow row = new ExecutionTraceRow();
        row.setTracePayload("{\"terminatedBy\":\"max_steps\"}");
        assertThat(service.extractPatternKey(row)).isEqualTo("step:limit:exceeded");
    }

    @Test
    void mineFailurePatternsSkipsWhenEmpty() {
        when(traceMapper.findUnanalyzedByStatus(anyString(), anyInt())).thenReturn(List.of());

        service.mineFailurePatterns();

        verify(failurePatternMapper, never()).insert(any());
        verify(traceMapper, never()).markPatternAnalyzed(any());
    }
}
