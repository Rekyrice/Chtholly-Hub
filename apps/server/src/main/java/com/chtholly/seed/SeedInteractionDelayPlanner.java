package com.chtholly.seed;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Provides human-like delay windows for seeded discussion rounds.
 */
public interface SeedInteractionDelayPlanner {

    Duration delayForRound(int round);

    static SeedInteractionDelayPlanner random() {
        return round -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            return switch (round) {
                case 1 -> Duration.ofMinutes(random.nextLong(5, 31));
                case 2 -> Duration.ofMinutes(random.nextLong(10, 61));
                default -> Duration.ofMinutes(random.nextLong(30, 121));
            };
        };
    }
}
