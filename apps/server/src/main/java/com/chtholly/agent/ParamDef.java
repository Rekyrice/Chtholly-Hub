package com.chtholly.agent;

import java.util.List;

/** Declarative tool parameter schema used by prompt rendering and runtime validation. */
public record ParamDef(
        String description,
        Class<?> type,
        boolean required,
        Integer minLength,
        Integer maxLength,
        Long minimum,
        Long maximum,
        List<String> enumValues) {

    public ParamDef {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Parameter description must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Parameter type must not be null");
        }
        if (minLength != null && minLength < 0) {
            throw new IllegalArgumentException("Parameter minLength must not be negative");
        }
        if (maxLength != null && maxLength < 0) {
            throw new IllegalArgumentException("Parameter maxLength must not be negative");
        }
        if (minLength != null && maxLength != null && minLength > maxLength) {
            throw new IllegalArgumentException("Parameter minLength must not exceed maxLength");
        }
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new IllegalArgumentException("Parameter minimum must not exceed maximum");
        }
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        if (enumValues.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Parameter enumValues must not contain blanks");
        }
    }

    /** Backward-compatible unconstrained parameter declaration. */
    public ParamDef(String description, Class<?> type, boolean required) {
        this(description, type, required, null, null, null, null, List.of());
    }

    /** Creates a bounded string parameter. */
    public static ParamDef string(
            String description,
            boolean required,
            int minLength,
            int maxLength) {
        return new ParamDef(
                description,
                String.class,
                required,
                minLength,
                maxLength,
                null,
                null,
                List.of());
    }

    /** Creates a bounded integer parameter. */
    public static ParamDef integer(
            String description,
            boolean required,
            long minimum,
            long maximum) {
        return new ParamDef(
                description,
                Integer.class,
                required,
                null,
                null,
                minimum,
                maximum,
                List.of());
    }

    /** Creates a string parameter constrained to a closed set of values. */
    public static ParamDef enumString(
            String description,
            boolean required,
            List<String> enumValues) {
        if (enumValues == null || enumValues.isEmpty()) {
            throw new IllegalArgumentException("Enum parameter must declare at least one value");
        }
        return new ParamDef(
                description,
                String.class,
                required,
                null,
                null,
                null,
                null,
                enumValues);
    }
}
