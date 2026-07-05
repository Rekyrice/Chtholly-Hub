package com.chtholly.agent.eval;

/**
 * Request sent to the agent responder.
 *
 * @param question evaluation question
 * @param userId synthetic evaluation user id
 */
public record EvaluationAgentRequest(EvaluationQuestion question, long userId) {
}
