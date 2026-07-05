package com.chtholly.agent.eval;

import java.util.Map;

/**
 * Scoring dimension used by LLM-as-judge.
 *
 * @param key machine-readable dimension key
 * @param name display name
 * @param description scoring intent
 * @param rubric score rubric keyed by score value
 */
public record EvaluationDimension(
        String key,
        String name,
        String description,
        Map<String, String> rubric
) {
}
