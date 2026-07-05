package com.chtholly.seed;

/**
 * Runtime options for seed generation.
 *
 * @param mode seed mode
 * @param dryRun true to preview without writing database rows
 */
public record SeedRunOptions(SeedRunMode mode, boolean dryRun) {
}
