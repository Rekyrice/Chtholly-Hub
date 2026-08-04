package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentMemoryCommitter;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Commits validated answers and emits the stable client event protocol in causal order.
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentTurnCompletion {

    private final ObjectMapper objectMapper;
    private final AgentMemoryCommitter memoryCommitter;

    /**
     * Creates the terminal answer boundary.
     *
     * @param objectMapper JSON payload mapper
     * @param memoryCommitter durable memory boundary
     */
    public AgentTurnCompletion(
            ObjectMapper objectMapper,
            AgentMemoryCommitter memoryCommitter) {
        this.objectMapper = objectMapper;
        this.memoryCommitter = memoryCommitter;
    }

    /**
     * Persists a validated exchange before emitting its delta and final events.
     *
     * @param memory conversation memory
     * @param question user question
     * @param answer validated answer
     * @param budget effective turn budget
     * @param control canonical turn identity
     * @param trace execution trace
     * @param sink client event sink
     * @param modelFirstTokenMs optional first model token time from turn start
     * @param safeAnswerReadyMs safe answer readiness time from turn start
     */
    public void completeVisibleAnswer(
            AgentConversationMemory memory,
            String question,
            String answer,
            AgentTurnBudget budget,
            AgentTurnControl control,
            AgentExecutionTrace trace,
            Consumer<AgentEvent> sink,
            Long modelFirstTokenMs,
            long safeAnswerReadyMs) {
        budget.check("client_delivery");
        memoryCommitter.commit(memory, question, answer, budget, control, trace);
        trace.terminateFinalAnswer(answer);
        budget.check("client_delivery");
        if (answer != null && !answer.isBlank()) {
            emitContent(sink, "delta", answer);
            trace.recordAnswerTiming(
                    modelFirstTokenMs,
                    safeAnswerReadyMs,
                    System.currentTimeMillis() - trace.getStartedAtMs());
        }
        emitContent(sink, "final", answer == null ? "" : answer);
    }

    /**
     * Emits the stable error event without exposing internal exception details.
     *
     * @param sink client event sink
     * @param message user-visible error message
     */
    public void emitError(Consumer<AgentEvent> sink, String message) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("message", message);
        AgentEvent.send(sink, "error", data);
    }

    private void emitContent(Consumer<AgentEvent> sink, String type, String content) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("content", content);
        AgentEvent.send(sink, type, data);
    }
}
