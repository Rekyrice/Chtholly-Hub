package com.chtholly.agent.eval;

/**
 * Produces an agent answer for an evaluation question.
 */
@FunctionalInterface
public interface AgentResponder {

    /**
     * Answers one evaluation question.
     *
     * @param request evaluation request
     * @return agent response text
     */
    String answer(EvaluationAgentRequest request) throws Exception;
}
