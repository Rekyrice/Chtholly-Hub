package com.chtholly.agent;

import java.util.Map;
import java.util.Optional;

/** 根据 {@link AgentTool#parameterSchema()} 校验 LLM 传入的工具参数。 */
public final class AgentToolParamValidator {

    private AgentToolParamValidator() {}

    /**
     * @return 校验失败时的 observation 文本；通过则 empty
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
            return true;
        }
        if (expected == Integer.class || expected == int.class) {
            if (value instanceof Number) {
                return true;
            }
            if (value instanceof String s) {
                try {
                    Integer.parseInt(s.trim());
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return false;
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
}
