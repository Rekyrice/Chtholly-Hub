package com.chtholly.agent.eval;

/**
 * Evaluation result for one test question.
 *
 * @param questionId stable question id
 * @param category scenario bucket
 * @param question user question
 * @param response agent response
 * @param scores judge scores
 */
public record QuestionEvaluationResult(
        String questionId,
        String category,
        String question,
        String response,
        EvaluationScores scores
) {
}
