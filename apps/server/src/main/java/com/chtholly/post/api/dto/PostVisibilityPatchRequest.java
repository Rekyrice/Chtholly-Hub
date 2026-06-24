package com.chtholly.post.api.dto;

import jakarta.validation.constraints.NotBlank;

public record PostVisibilityPatchRequest(
        @NotBlank String visible
) {}