package com.chtholly.agent.eval;

import java.util.List;

/**
 * Input passed to an LLM-as-judge implementation.
 *
 * @param question evaluated question
 * @param response agent response
 * @param dimensions scoring dimensions
 */
public record JudgeRequest(
        EvaluationQuestion question,
        String response,
        List<EvaluationDimension> dimensions
) {
    public List<String> dimensionKeys() {
        return dimensions.stream().map(EvaluationDimension::key).toList();
    }
}
