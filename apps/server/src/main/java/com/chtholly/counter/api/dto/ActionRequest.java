package com.chtholly.counter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Identifies the entity targeted by a like or favorite mutation. */
@Data
public class ActionRequest {
    @NotBlank
    @Size(max = 32)
    @Pattern(regexp = "[A-Za-z0-9._-]+")
    private String entityType;

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "[A-Za-z0-9._-]+")
    private String entityId;
}
