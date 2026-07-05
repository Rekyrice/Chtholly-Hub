package com.chtholly.seed;

import java.time.Instant;
import java.time.LocalDate;

/**
 * User row inserted by seed scripts.
 */
public record SeedUserRow(
        long id,
        String email,
        String nickname,
        String avatar,
        String bio,
        String handle,
        String gender,
        LocalDate birthday,
        String school,
        String tagsJson,
        Instant createdAt,
        Instant updatedAt
) {
}
