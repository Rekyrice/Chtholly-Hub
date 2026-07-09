package com.chtholly.agent.eval;

import java.util.List;

/**
 * Evaluation result for one test question.
 *
 * @param questionId stable question id
 * @param category scenario bucket
 * @param question user question
 * @param response agent response
 * @param scores judge scores
 * @param consistencyResponses optional repeated responses for style consistency check
 */
public record QuestionEvaluationResult(
        String questionId,
        String category,
        String question,
        String response,
        EvaluationScores scores,
        List<String> consistencyResponses
) {

    public QuestionEvaluationResult(
            String questionId,
            String category,
            String question,
            String response,
            EvaluationScores scores) {
        this(questionId, category, question, response, scores, List.of());
    }
}
