package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentMemoryCommitter;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentTurnCompletionTest {

    private final AgentMemoryCommitter memoryCommitter = mock(AgentMemoryCommitter.class);
    private final AgentTurnCompletion completion =
            new AgentTurnCompletion(new ObjectMapper(), memoryCommitter);
    private final AgentConversationMemory memory = mock(AgentConversationMemory.class);
    private final AgentExecutionTrace trace = mock(AgentExecutionTrace.class);

    @Test
    void commitsMemoryBeforeDeliveringDeltaAndFinalEvents() {
        AgentTurnControl control = AgentTurnControl.standalone("session", Duration.ofSeconds(1));
        @SuppressWarnings("unchecked")
        Consumer<AgentEvent> sink = mock(Consumer.class);

        completion.completeVisibleAnswer(
                memory,
                "question",
                "answer",
                control.budget(),
                control,
                trace,
                sink,
                12L,
                25L);

        InOrder order = inOrder(memoryCommitter, trace, sink);
        order.verify(memoryCommitter).commit(
                eq(memory), eq("question"), eq("answer"), eq(control.budget()), eq(control), eq(trace));
        order.verify(trace).terminateFinalAnswer("answer");
        order.verify(sink).accept(any(AgentEvent.class));
        order.verify(trace).recordAnswerTiming(eq(12L), eq(25L), anyLong());
        order.verify(sink).accept(any(AgentEvent.class));
    }

    @Test
    void emitsStableDeltaAndFinalPayloads() {
        AgentTurnControl control = AgentTurnControl.standalone("session", Duration.ofSeconds(1));
        List<AgentEvent> events = new ArrayList<>();

        completion.completeVisibleAnswer(
                null,
                "question",
                "answer",
                control.budget(),
                control,
                trace,
                events::add,
                null,
                0L);

        assertThat(events).extracting(AgentEvent::type).containsExactly("delta", "final");
        assertThat(events).allSatisfy(event ->
                assertThat(event.data().path("content").asText()).isEqualTo("answer"));
    }

    @Test
    void emitsStableErrorPayload() {
        List<AgentEvent> events = new ArrayList<>();

        completion.emitError(events::add, "temporarily unavailable");

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("error");
            assertThat(event.data().path("message").asText()).isEqualTo("temporarily unavailable");
        });
    }
}
