package com.chtholly.agent.response;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.CharacterSoulService;
import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.runtime.AgentTurnBudget;
import com.chtholly.agent.runtime.AgentTurnCompletion;
import com.chtholly.agent.runtime.AgentTurnControl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentBoundaryResponseServiceTest {

    private final AgentLlmInvoker llmInvoker = mock(AgentLlmInvoker.class);
    private final AgentObservationService observations = mock(AgentObservationService.class);
    private final AgentTurnCompletion completion = mock(AgentTurnCompletion.class);
    private final CharacterSoulService soul = mock(CharacterSoulService.class);
    private final AgentProperties properties = new AgentProperties();
    private final AgentBoundaryResponseService service = new AgentBoundaryResponseService(
            llmInvoker, properties, soul, observations, completion);

    @Test
    void validatesPersonaCopyAndCompletesItThroughTheSharedDeliveryBoundary() {
        properties.setModel("test-model");
        when(soul.getSoulContent()).thenReturn("soul");
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.just("请告诉我想解释哪一个对象呢？"));
        Observation agentSpan = mock(Observation.class);
        when(observations.startLlmSpan(agentSpan, "test-model")).thenReturn(mock(Observation.class));
        AgentTurnControl control = AgentTurnControl.standalone("session", Duration.ofSeconds(2));
        AgentExecutionTrace trace = new AgentExecutionTrace(7L, control, 4);
        AgentConversationMemory memory = mock(AgentConversationMemory.class);
        @SuppressWarnings("unchecked")
        Consumer<AgentEvent> sink = mock(Consumer.class);

        service.complete(
                AgentExecutionTrace.OutcomeReason.NEEDS_CLARIFICATION,
                "page-explain",
                "target_missing",
                "question",
                memory,
                sink,
                trace,
                agentSpan,
                control.budget());

        ArgumentCaptor<String> answer = ArgumentCaptor.forClass(String.class);
        verify(completion).completeVisibleAnswer(
                eq(memory),
                eq("question"),
                answer.capture(),
                eq(control.budget()),
                eq(control),
                eq(trace),
                eq(sink),
                any(),
                anyLong());
        assertThat(answer.getValue()).isEqualTo("请告诉我想解释哪一个对象呢？");
        assertThat(boundaryLlmDetails(trace).path("budget_after_ms").asLong()).isPositive();
    }

    @Test
    void classifiesAStreamFailureAfterCancellationAsCancelled() {
        properties.setModel("test-model");
        when(soul.getSoulContent()).thenReturn("soul");
        Observation agentSpan = mock(Observation.class);
        when(observations.startLlmSpan(agentSpan, "test-model")).thenReturn(mock(Observation.class));
        AgentTurnControl control = AgentTurnControl.standalone("session", Duration.ofSeconds(2));
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.defer(() -> {
                    control.cancel();
                    return Flux.error(new IllegalStateException("stream failed after cancellation"));
                }));
        AgentExecutionTrace trace = new AgentExecutionTrace(7L, control, 4);

        assertThatThrownBy(() -> service.complete(
                AgentExecutionTrace.OutcomeReason.NO_EVIDENCE,
                "",
                "retrieval_empty",
                "question",
                null,
                event -> { },
                trace,
                agentSpan,
                control.budget()))
                .isInstanceOf(AgentTurnBudget.UnavailableException.class);

        assertThat(boundaryLlmDetails(trace).path("error_code").asText())
                .isEqualTo("TURN_CANCELLED");
    }

    @Test
    void boundaryCopyFailurePreservesThePrimaryFailureClassification() {
        properties.setModel("test-model");
        when(soul.getSoulContent()).thenReturn("soul");
        Observation agentSpan = mock(Observation.class);
        when(observations.startLlmSpan(agentSpan, "test-model")).thenReturn(mock(Observation.class));
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.error(new IllegalStateException("boundary model unavailable")));
        AgentTurnControl control = AgentTurnControl.standalone("session", Duration.ofSeconds(2));
        AgentExecutionTrace trace = new AgentExecutionTrace(7L, control, 4);
        trace.markFailure(AgentExecutionTrace.FailureType.RETRIEVAL_EMPTY);
        trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.NO_EVIDENCE);

        service.complete(
                AgentExecutionTrace.OutcomeReason.NO_EVIDENCE,
                "",
                "retrieval_empty",
                "question",
                null,
                event -> { },
                trace,
                agentSpan,
                control.budget());

        assertThat(trace.getFailureType())
                .isEqualTo(AgentExecutionTrace.FailureType.RETRIEVAL_EMPTY);
        assertThat(trace.getOutcomeReason())
                .isEqualTo(AgentExecutionTrace.OutcomeReason.NO_EVIDENCE);
    }

    private JsonNode boundaryLlmDetails(AgentExecutionTrace trace) {
        JsonNode payload = new ObjectMapper().valueToTree(trace.toPayloadMap());
        for (JsonNode event : payload.path("events")) {
            JsonNode details = event.path("details");
            if ("BOUNDARY_RESPONSE".equals(details.path("purpose").asText())) {
                return event;
            }
        }
        throw new AssertionError("missing BOUNDARY_RESPONSE trace event");
    }
}
