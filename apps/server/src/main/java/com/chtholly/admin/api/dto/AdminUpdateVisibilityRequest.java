package com.chtholly.admin.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminUpdateVisibilityRequest(
        @NotBlank(message = "可见性不能为空") String visible
) {}
