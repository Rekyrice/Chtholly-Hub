package com.chtholly.seed;

/**
 * Generates characterful seed text. Implementations may use LLM or templates.
 */
public interface SeedTextGenerator {

    String bangumiReview(BangumiSubjectSeed subject);

    String postBody(SeedAccountProfile account, SeedPostPlan postPlan);

    String comment(SeedAccountProfile commenter, SeedAccountProfile author, SeedPostPlan postPlan);
}
