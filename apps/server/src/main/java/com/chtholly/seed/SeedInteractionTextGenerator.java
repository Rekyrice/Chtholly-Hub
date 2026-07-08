package com.chtholly.seed;

/**
 * Generates a persona-specific comment for a seed interaction.
 */
@FunctionalInterface
public interface SeedInteractionTextGenerator {

    String generateReply(SeedInteractionAccount account, SeedInteractionContext context);
}
