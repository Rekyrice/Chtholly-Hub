package com.chtholly.agent.memory;

import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.runtime.AgentBoundedCallExecutor;
import com.chtholly.agent.runtime.AgentTurnBudget;
import com.chtholly.agent.runtime.AgentTurnControl;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentMemoryCommitterTest {

    private final AgentMemoryCommitter committer =
            new AgentMemoryCommitter(new AgentBoundedCallExecutor());
    private final AgentConversationMemory memory = mock(AgentConversationMemory.class);
    private final AgentExecutionTrace trace = mock(AgentExecutionTrace.class);

    @Test
    void commitsDirectMemoryAndRecordsTheOutcome() {
        AgentTurnControl control = AgentTurnControl.standalone("session", Duration.ofSeconds(1));
        when(memory.addExchange(any(), any())).thenReturn(true);

        committer.commit(memory, " question ", "answer", control.budget(), control, trace);

        verify(memory).addExchange(
                argThat(turn -> turn.role() == AgentTurn.Role.USER
                        && "question".equals(turn.content())),
                argThat(turn -> turn.role() == AgentTurn.Role.ASSISTANT
                        && "answer".equals(turn.content())));
        verify(trace).recordMemoryWrite("COMMITTED", "");
    }

    @Test
    void rejectsAStaleFencedWriteBeforeAnyAnswerCanBeDelivered() {
        AgentTurnControl control = AgentTurnControl.create(
                "request", "turn", "session", "connection", Duration.ofSeconds(1));
        when(memory.addExchange(any(), any(), any(), anyLong())).thenReturn(
                new AgentMemoryStore.MemoryWriteResult(
                        AgentMemoryStore.MemoryWriteStatus.REJECTED,
                        "STALE_TURN"));

        assertThatThrownBy(() -> committer.commit(
                memory, "question", "answer", control.budget(), control, trace))
                .isInstanceOf(AgentMemoryWriteException.class)
                .hasMessage("STALE_TURN");
        verify(trace).recordMemoryWrite("REJECTED", "STALE_TURN");
    }

    @Test
    void mapsAStoreDeadlineRejectionBackToTheTurnBudgetProtocol() {
        AgentTurnControl control = AgentTurnControl.create(
                "request", "turn", "session", "connection", Duration.ofSeconds(1));
        when(memory.addExchange(any(), any(), any(), anyLong())).thenReturn(
                new AgentMemoryStore.MemoryWriteResult(
                        AgentMemoryStore.MemoryWriteStatus.REJECTED,
                        "DEADLINE_EXPIRED"));

        assertThatThrownBy(() -> committer.commit(
                memory, "question", "answer", control.budget(), control, trace))
                .isInstanceOfSatisfying(AgentTurnBudget.UnavailableException.class, unavailable -> {
                    org.assertj.core.api.Assertions.assertThat(unavailable.reason())
                            .isEqualTo(AgentTurnBudget.UnavailableReason.TIMEOUT);
                    org.assertj.core.api.Assertions.assertThat(unavailable.stage())
                            .isEqualTo("memory_write");
                });
        verify(trace).recordMemoryWrite("REJECTED", "DEADLINE_EXPIRED");
    }
}
