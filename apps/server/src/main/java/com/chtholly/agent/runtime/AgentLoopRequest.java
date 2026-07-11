package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentTool;

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
 */
public record AgentLoopRequest(
        String systemPrompt,
        String question,
        long userId,
        String historyBlock,
        Map<String, AgentTool> tools,
        int maxSteps
) {
    public AgentLoopRequest {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        question = question == null ? "" : question;
        historyBlock = historyBlock == null ? "" : historyBlock;
        tools = tools == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(tools));
        maxSteps = Math.max(1, maxSteps);
    }
}
