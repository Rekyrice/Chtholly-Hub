package com.chtholly.seed;

/**
 * Scores generated seed comments before publication.
 */
@FunctionalInterface
public interface SeedInteractionQualityEvaluator {

    double evaluate(SeedInteractionAccount account, SeedInteractionContext context, String comment);
}
