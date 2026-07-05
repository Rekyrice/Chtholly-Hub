package com.chtholly.agent.eval;

import java.util.List;
import java.util.Map;

/**
 * Aggregated evaluation report written to disk as JSON.
 *
 * @param runId stable run id
 * @param quick whether this was a quick run
 * @param totalQuestions evaluated question count
 * @param dimensionAverages average score per dimension
 * @param overallScore overall average score
 * @param previousOverallScore previous run overall score, if available
 * @param overallDelta score delta against previous run, if available
 * @param worstQuestions lowest-scoring questions
 * @param results per-question details
 */
public record EvaluationReport(
        String runId,
        boolean quick,
        int totalQuestions,
        Map<String, Double> dimensionAverages,
        double overallScore,
        Double previousOverallScore,
        Double overallDelta,
        List<QuestionEvaluationResult> worstQuestions,
        List<QuestionEvaluationResult> results
) {
    public EvaluationReport withComparison(Double previousScore) {
        if (previousScore == null) {
            return this;
        }
        return new EvaluationReport(
                runId,
                quick,
                totalQuestions,
                dimensionAverages,
                overallScore,
                previousScore,
                round(overallScore - previousScore),
                worstQuestions,
                results);
    }

    static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
