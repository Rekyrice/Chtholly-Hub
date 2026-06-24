package com.chtholly.profile.api.dto;

import java.time.LocalDate;

public record ProfileResponse(
        Long id,
        String nickname,
        String avatar,
        String bio,
        String handle,
        String gender,
        LocalDate birthday,
        String school,
        String phone,
        String email,
        String tagJson
) {}