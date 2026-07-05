package com.chtholly.seed;

import java.time.LocalDate;
import java.util.List;

/**
 * Seed user persona and profile data.
 */
public record SeedAccountProfile(
        String handle,
        String nickname,
        String bio,
        String avatar,
        String gender,
        LocalDate birthday,
        String school,
        List<String> tags,
        String persona
) {
}
