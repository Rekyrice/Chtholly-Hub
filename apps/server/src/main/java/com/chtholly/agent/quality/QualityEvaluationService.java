package com.chtholly.agent.quality;

/**
 * Evaluates content quality across Agent workflows.
 */
public interface QualityEvaluationService {

    /**
     * Evaluates content quality with configurable dimensions.
     *
     * @param content  Text content to evaluate.
     * @param context  Additional context such as title, tags, or persona.
     * @param criteria Evaluation criteria.
     * @return evaluation result.
     */
    QualityResult evaluate(String content, String context, QualityCriteria criteria);
}
