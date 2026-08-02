package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.evidence.EvidenceSet;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable input for one bounded ReAct loop execution.
 *
 * @param systemPrompt assembled system prompt
 * @param question current user question
 * @param userId authenticated user identifier
 * @param historyBlock formatted conversation history
 * @param tools tools addressable by action name
 * @param maxSteps maximum number of model decisions
 * @param turnBudget shared whole-turn budget, or {@code null} for legacy callers
 * @param evidenceSet immutable initial retrieval evidence
 * @param evidenceRequired whether the turn requires evidence-backed citations
 */
public record AgentLoopRequest(
        String systemPrompt,
        String question,
        long userId,
        String historyBlock,
        Map<String, AgentTool> tools,
        int maxSteps,
        AgentTurnBudget turnBudget,
        EvidenceSet evidenceSet,
        boolean evidenceRequired
) {
    public AgentLoopRequest {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        question = question == null ? "" : question;
        historyBlock = historyBlock == null ? "" : historyBlock;
        tools = tools == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(tools));
        maxSteps = Math.max(1, maxSteps);
        evidenceSet = evidenceSet == null ? EvidenceSet.empty() : evidenceSet;
    }

    /** Creates a request with a whole-turn deadline and no initial evidence. */
    public AgentLoopRequest(
            String systemPrompt,
            String question,
            long userId,
            String historyBlock,
            Map<String, AgentTool> tools,
            int maxSteps,
            AgentTurnBudget turnBudget) {
        this(systemPrompt, question, userId, historyBlock, tools, maxSteps,
                turnBudget, EvidenceSet.empty(), false);
    }

    /** Creates an evidence-aware request without a whole-turn deadline. */
    public AgentLoopRequest(
            String systemPrompt,
            String question,
            long userId,
            String historyBlock,
            Map<String, AgentTool> tools,
            int maxSteps,
            EvidenceSet evidenceSet,
            boolean evidenceRequired) {
        this(systemPrompt, question, userId, historyBlock, tools, maxSteps,
                null, evidenceSet, evidenceRequired);
    }

    /** Creates a request without a whole-turn deadline for legacy and isolated tests. */
    public AgentLoopRequest(
            String systemPrompt,
            String question,
            long userId,
            String historyBlock,
            Map<String, AgentTool> tools,
            int maxSteps) {
        this(systemPrompt, question, userId, historyBlock, tools, maxSteps,
                null, EvidenceSet.empty(), false);
    }
}
