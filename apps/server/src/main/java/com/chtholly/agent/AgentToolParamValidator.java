package com.chtholly.agent;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** Validates LLM-provided tool input against {@link AgentTool#parameterSchema()}. */
public final class AgentToolParamValidator {

    private AgentToolParamValidator() {}

    /**
     * Validates tool input values against the declared parameter schema.
     *
     * @param input tool input values; {@code null} is treated as an empty map
     * @param schema declared parameter schema; {@code null} or empty skips validation
     * @return an optional validation error observation, or empty when validation succeeds
     */
    public static Optional<String> validate(Map<String, Object> input, Map<String, ParamDef> schema) {
        if (schema == null || schema.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> params = input == null ? Map.of() : input;
        for (Map.Entry<String, ParamDef> entry : schema.entrySet()) {
            String name = entry.getKey();
            ParamDef def = entry.getValue();
            Object value = params.get(name);

            if (def.required() && isMissing(value)) {
                return Optional.of("Missing required parameter: " + name);
            }
            if (!isMissing(value) && !matchesType(value, def.type())) {
                return Optional.of("Invalid type for parameter: " + name);
            }
            if (!isMissing(value)) {
                Optional<String> constraintError = validateConstraints(name, value, def);
                if (constraintError.isPresent()) {
                    return constraintError;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validateConstraints(String name, Object value, ParamDef def) {
        if (value instanceof String text) {
            String normalized = text.strip();
            int length = normalized.codePointCount(0, normalized.length());
            if (def.minLength() != null && length < def.minLength()) {
                return Optional.of("Parameter " + name + " must contain at least "
                        + def.minLength() + " characters");
            }
            if (def.maxLength() != null && length > def.maxLength()) {
                return Optional.of("Parameter " + name + " must contain at most "
                        + def.maxLength() + " characters");
            }
            if (!def.enumValues().isEmpty() && !def.enumValues().contains(normalized)) {
                return Optional.of("Parameter " + name + " must be one of: "
                        + String.join(", ", def.enumValues()));
            }
        }

        OptionalLong integer = integerValue(value);
        if (integer.isPresent()) {
            long numericValue = integer.getAsLong();
            if (def.minimum() != null && numericValue < def.minimum()) {
                return Optional.of("Parameter " + name + " must be at least " + def.minimum());
            }
            if (def.maximum() != null && numericValue > def.maximum()) {
                return Optional.of("Parameter " + name + " must be at most " + def.maximum());
            }
        }
        return Optional.empty();
    }

    private static boolean isMissing(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return s.isBlank();
        }
        return false;
    }

    private static boolean matchesType(Object value, Class<?> expected) {
        if (expected == String.class) {
            return value instanceof String;
        }
        if (expected == Integer.class || expected == int.class) {
            return integerValue(value).isPresent();
        }
        if (expected == Boolean.class || expected == boolean.class) {
            if (value instanceof Boolean) {
                return true;
            }
            if (value instanceof String s) {
                String v = s.trim().toLowerCase();
                return "true".equals(v) || "false".equals(v);
            }
            return false;
        }
        return expected.isInstance(value);
    }

    private static OptionalLong integerValue(Object value) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
                    ? OptionalLong.of(number)
                    : OptionalLong.empty();
        }
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            if (Double.isFinite(decimal)
                    && decimal == Math.rint(decimal)
                    && decimal >= Integer.MIN_VALUE
                    && decimal <= Integer.MAX_VALUE) {
                return OptionalLong.of((long) decimal);
            }
            return OptionalLong.empty();
        }
        if (value instanceof String text) {
            try {
                return OptionalLong.of(Integer.parseInt(text.strip()));
            } catch (NumberFormatException exception) {
                return OptionalLong.empty();
            }
        }
        return OptionalLong.empty();
    }
}
