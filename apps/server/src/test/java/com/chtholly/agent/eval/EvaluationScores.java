package com.chtholly.agent.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Judge scores for one question.
 *
 * @param dimensions dimension score map
 * @param overall weighted or arithmetic overall score
 */
public record EvaluationScores(Map<String, EvaluationScore> dimensions, double overall) {

    public static EvaluationScores uniform(List<String> keys, int score, String reason) {
        Map<String, EvaluationScore> values = new LinkedHashMap<>();
        for (String key : keys) {
            values.put(key, new EvaluationScore(score, reason));
        }
        return new EvaluationScores(values, score);
    }

    public int score(String key) {
        EvaluationScore score = dimensions.get(key);
        return score == null ? 0 : score.score();
    }

    public boolean hasDimension(String key) {
        return dimensions.containsKey(key);
    }

    public EvaluationScores withDimension(String key, EvaluationScore score) {
        Map<String, EvaluationScore> copy = new LinkedHashMap<>(dimensions);
        copy.put(key, score);
        double overall = copy.values().stream().mapToInt(EvaluationScore::score).average().orElse(0.0);
        return new EvaluationScores(copy, EvaluationReport.round(overall));
    }

    public String reason(String key) {
        EvaluationScore score = dimensions.get(key);
        return score == null ? "" : score.reason();
    }
}
