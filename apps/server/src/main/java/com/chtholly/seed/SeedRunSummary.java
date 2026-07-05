package com.chtholly.seed;

/**
 * Summary returned by one seed run.
 *
 * @param mode seed mode
 * @param dryRun whether writes were skipped
 * @param skipped whether an existing seed marker caused an idempotent skip
 * @param recommendations recommendation count
 * @param accounts seed account count
 * @param posts seed post count
 * @param comments comment count
 * @param follows follow relation count
 */
public record SeedRunSummary(
        SeedRunMode mode,
        boolean dryRun,
        boolean skipped,
        int recommendations,
        int accounts,
        int posts,
        int comments,
        int follows
) {
    public static SeedRunSummary skipped(SeedRunMode mode, boolean dryRun) {
        return new SeedRunSummary(mode, dryRun, true, 0, 0, 0, 0, 0);
    }
}
