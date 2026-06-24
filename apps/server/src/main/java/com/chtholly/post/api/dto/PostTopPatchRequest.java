package com.chtholly.post.api.dto;

import jakarta.validation.constraints.NotNull;

public record PostTopPatchRequest(
        @NotNull Boolean isTop
) {}