package com.chtholly.agent.eval;

import java.util.List;

/**
 * One evaluation prompt with optional user and history context.
 *
 * @param id stable question id
 * @param category scenario bucket
 * @param text user question sent to the agent
 * @param userProfile optional profile context shown to the judge
 * @param historySummary optional history summary shown to the judge
 * @param expectedKeywords optional factual keywords for knowledge verification
 * @param consistencyCheck whether to run repeat-style consistency evaluation
 */
public record EvaluationQuestion(
        String id,
        String category,
        String text,
        String userProfile,
        String historySummary,
        List<String> expectedKeywords,
        boolean consistencyCheck
) {
}
