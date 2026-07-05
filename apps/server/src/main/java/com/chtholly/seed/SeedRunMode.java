package com.chtholly.seed;

import java.util.Locale;

/**
 * Seed runner execution modes.
 */
public enum SeedRunMode {
    FULL,
    BANGUMI,
    ACCOUNTS;

    public String markerKey() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SeedRunMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return FULL;
        }
        return SeedRunMode.valueOf(raw.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
