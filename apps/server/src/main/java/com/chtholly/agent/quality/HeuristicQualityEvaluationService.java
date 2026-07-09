package com.chtholly.agent.quality;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Rule-based fallback quality evaluator.
 */
@Component
public class HeuristicQualityEvaluationService implements QualityEvaluationService {

    private static final double BASE_SCORE = 2.8;

    /**
     * Evaluates content with deterministic fallback rules.
     *
     * @param content  Text content to evaluate.
     * @param context  Additional context such as title, tags, or persona.
     * @param criteria Evaluation criteria.
     * @return evaluation result.
     */
    @Override
    public QualityResult evaluate(String content, String context, QualityCriteria criteria) {
        QualityCriteria safeCriteria = criteria == null ? QualityCriteria.articleQuality() : criteria;
        String safeContent = content == null ? "" : content.trim();
        if (safeContent.isBlank()) {
            return result(1.0, "内容为空，无法判断质量。", safeCriteria);
        }

        double score = BASE_SCORE;
        score += lengthBonus(safeContent);
        score += structureBonus(safeContent);
        score += contextBonus(safeContent, context);
        score = clamp(score);

        String feedback = score >= safeCriteria.minScore()
                ? "内容具备基本结构和上下文关联，可以通过。"
                : "内容还偏薄，建议补充细节或更清晰的结构。";
        return result(score, feedback, safeCriteria);
    }

    private QualityResult result(double score, String feedback, QualityCriteria criteria) {
        Map<String, Double> dimensionScores = new LinkedHashMap<>();
        for (QualityCriteria.Dimension dimension : criteria.dimensions()) {
            dimensionScores.put(dimension.name(), score);
        }
        return new QualityResult(score, feedback, score >= criteria.minScore(), dimensionScores);
    }

    private static double lengthBonus(String content) {
        int length = content.length();
        if (length >= 800) {
            return 0.7;
        }
        if (length >= 300) {
            return 0.45;
        }
        if (length >= 50) {
            return 0.25;
        }
        return -0.4;
    }

    private static double structureBonus(String content) {
        double bonus = 0.0;
        if (content.contains("\n\n")) {
            bonus += 0.2;
        }
        if (content.contains("## ") || content.contains("### ")) {
            bonus += 0.35;
        }
        if (content.contains("```")) {
            bonus += 0.2;
        }
        return bonus;
    }

    private static double contextBonus(String content, String context) {
        if (context == null || context.isBlank()) {
            return 0.0;
        }
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        double bonus = 0.0;
        for (String token : context.split("[\\s,，。:：/|、]+")) {
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            if (normalized.length() >= 2 && normalizedContent.contains(normalized)) {
                bonus += 0.15;
                if (bonus >= 0.45) {
                    break;
                }
            }
        }
        return bonus;
    }

    private static double clamp(double score) {
        return Math.max(1.0, Math.min(5.0, score));
    }
}
