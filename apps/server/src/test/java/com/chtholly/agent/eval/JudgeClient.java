package com.chtholly.agent.eval;

/**
 * Scores one agent response.
 */
@FunctionalInterface
public interface JudgeClient {

    /**
     * Scores the response according to configured dimensions.
     *
     * @param request judge request
     * @return dimension scores and overall score
     */
    EvaluationScores judge(JudgeRequest request) throws Exception;
}
