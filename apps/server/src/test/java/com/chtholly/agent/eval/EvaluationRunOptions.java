package com.chtholly.agent.eval;

/**
 * Runtime options for an evaluation run.
 *
 * @param quickMode whether to use the quick subset
 * @param quickLimit number of questions in quick mode
 * @param userId synthetic user id used by the agent responder
 */
public record EvaluationRunOptions(boolean quickMode, int quickLimit, long userId) {

    public static EvaluationRunOptions quick() {
        return new EvaluationRunOptions(true, 10, 1L);
    }

    public static EvaluationRunOptions full() {
        return new EvaluationRunOptions(false, Integer.MAX_VALUE, 1L);
    }
}
