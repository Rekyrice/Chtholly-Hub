package com.chtholly.agent.memory;

import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.runtime.AgentBoundedCallExecutor;
import com.chtholly.agent.runtime.AgentTurnBudget;
import com.chtholly.agent.runtime.AgentTurnControl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Commits one complete conversation exchange before it becomes visible to the client.
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentMemoryCommitter {

    private final AgentBoundedCallExecutor boundedCallExecutor;

    /**
     * Creates the memory commit boundary.
     *
     * @param boundedCallExecutor bounded blocking-stage executor
     */
    public AgentMemoryCommitter(AgentBoundedCallExecutor boundedCallExecutor) {
        this.boundedCallExecutor = boundedCallExecutor;
    }

    /**
     * Persists the user and assistant turns with the current deadline and lease fence.
     *
     * @param memory conversation memory
     * @param question user question
     * @param answer validated assistant answer
     * @param budget effective turn budget
     * @param control canonical turn identity
     * @param trace execution trace
     * @throws AgentMemoryWriteException when the store rejects or cannot confirm the write
     * @throws AgentTurnBudget.UnavailableException when the write exceeds the turn deadline
     */
    public void commit(
            AgentConversationMemory memory,
            String question,
            String answer,
            AgentTurnBudget budget,
            AgentTurnControl control,
            AgentExecutionTrace trace) {
        if (memory == null || answer == null || answer.isBlank()) {
            return;
        }
        AgentMemoryStore.MemoryWriteResult result;
        try {
            result = boundedCallExecutor.execute(
                    () -> write(memory, question, answer, control, budget),
                    budget,
                    "memory_write");
        } catch (AgentTurnBudget.UnavailableException unavailable) {
            trace.recordMemoryWrite(
                    "UNKNOWN",
                    unavailable.reason() == AgentTurnBudget.UnavailableReason.TIMEOUT
                            ? "CALL_TIMEOUT"
                            : "TURN_CANCELLED");
            throw unavailable;
        }
        trace.recordMemoryWrite(result.status().name(), result.failureCode());
        if (result.committed()) {
            return;
        }
        if ("DEADLINE_EXPIRED".equals(result.failureCode())) {
            throw AgentTurnBudget.unavailableForStage(
                    AgentTurnBudget.UnavailableReason.TIMEOUT,
                    "memory_write");
        }
        throw new AgentMemoryWriteException(result.failureCode());
    }

    private AgentMemoryStore.MemoryWriteResult write(
            AgentConversationMemory memory,
            String question,
            String answer,
            AgentTurnControl control,
            AgentTurnBudget budget) {
        AgentTurn userTurn = AgentTurn.user(question.trim());
        AgentTurn assistantTurn = AgentTurn.assistant(answer);
        if ("direct".equals(control.connectionId())) {
            boolean committed = memory.addExchange(userTurn, assistantTurn);
            return new AgentMemoryStore.MemoryWriteResult(
                    committed
                            ? AgentMemoryStore.MemoryWriteStatus.COMMITTED
                            : AgentMemoryStore.MemoryWriteStatus.REJECTED,
                    committed ? "" : "STORE_REJECTED");
        }
        return memory.addExchange(
                userTurn,
                assistantTurn,
                control,
                budget.deadlineEpochMillis());
    }
}
