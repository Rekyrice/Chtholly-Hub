package com.chtholly.agent.observability;

import com.chtholly.agent.ParamDef;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Safe, bounded diagnostics for a single tool execution. */
public record AgentToolDiagnostics(
        String operation,
        String provider,
        String sourcePolicy,
        Map<String, Object> sanitizedInput,
        String outputPreview,
        String outputSha256,
        int outputChars,
        boolean outputTruncated,
        Integer resultCount,
        List<String> selectedIds,
        String errorCode) {

    private static final int MAX_SELECTED_IDS = 20;
    private static final int MAX_NESTING_DEPTH = 3;
    private static final int MAX_MAP_ENTRIES = 20;
    private static final int MAX_METADATA_CHARS = 128;

    public AgentToolDiagnostics {
        operation = boundedMetadata(defaultText(operation, "unknown"));
        provider = boundedMetadata(defaultText(provider, "internal"));
        sourcePolicy = boundedMetadata(defaultText(sourcePolicy, "unspecified"));
        sanitizedInput = immutableMap(sanitizedInput);
        String rawPreview = outputPreview == null ? "" : outputPreview;
        AgentTraceSanitizer.Preview safePreview = AgentTraceSanitizer.preview(rawPreview);
        outputTruncated = outputTruncated || safePreview.truncated();
        outputPreview = safePreview.text();
        outputSha256 = isSha256(outputSha256) ? outputSha256.toLowerCase() : "";
        outputChars = Math.max(0, outputChars);
        resultCount = resultCount == null ? null : Math.max(0, resultCount);
        selectedIds = immutableIds(selectedIds);
        errorCode = AgentTraceSanitizer.boundedRedactedText(errorCode, MAX_METADATA_CHARS);
    }

    /** Creates the default STANDARD capture for one tool. */
    public static AgentToolDiagnostics standard(
            String operation,
            Map<String, ParamDef> parameterSchema,
            Map<String, Object> input,
            String observation) {
        AgentTraceSanitizer.Preview preview = AgentTraceSanitizer.preview(observation);
        return new AgentToolDiagnostics(
                operation,
                "internal",
                "unspecified",
                AgentTraceSanitizer.sanitizeInput(parameterSchema, input),
                preview.text(),
                preview.sha256(),
                preview.chars(),
                preview.truncated(),
                null,
                List.of(),
                "");
    }

    /** Creates a no-input fallback without exposing a diagnostics exception. */
    public static AgentToolDiagnostics fallback(String operation, String observation) {
        return standard(operation, Map.of(), Map.of(), observation);
    }

    public AgentToolDiagnostics withProvider(String value) {
        return copy(value, sourcePolicy, resultCount, selectedIds, errorCode);
    }

    public AgentToolDiagnostics withSourcePolicy(String value) {
        return new AgentToolDiagnostics(operation, provider, value, sanitizedInput, outputPreview,
                outputSha256, outputChars, outputTruncated, resultCount, selectedIds, errorCode);
    }

    public AgentToolDiagnostics withResultCount(Integer value) {
        return new AgentToolDiagnostics(operation, provider, sourcePolicy, sanitizedInput, outputPreview,
                outputSha256, outputChars, outputTruncated, value, selectedIds, errorCode);
    }

    public AgentToolDiagnostics withSelectedIds(Collection<String> values) {
        return new AgentToolDiagnostics(operation, provider, sourcePolicy, sanitizedInput, outputPreview,
                outputSha256, outputChars, outputTruncated, resultCount,
                values == null ? List.of() : new ArrayList<>(values), errorCode);
    }

    public AgentToolDiagnostics withErrorCode(String value) {
        return new AgentToolDiagnostics(operation, provider, sourcePolicy, sanitizedInput, outputPreview,
                outputSha256, outputChars, outputTruncated, resultCount, selectedIds, value);
    }

    private AgentToolDiagnostics copy(
            String providerValue,
            String sourcePolicyValue,
            Integer resultCountValue,
            Collection<String> selectedIdsValue,
            String errorCodeValue) {
        return new AgentToolDiagnostics(operation, providerValue, sourcePolicyValue, sanitizedInput,
                outputPreview, outputSha256, outputChars, outputTruncated, resultCountValue,
                selectedIdsValue == null ? List.of() : new ArrayList<>(selectedIdsValue), errorCodeValue);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (count++ >= MAX_MAP_ENTRIES) {
                break;
            }
            String key = AgentTraceSanitizer.boundedRedactedText(entry.getKey(), MAX_METADATA_CHARS);
            if (AgentTraceSanitizer.isInternalKey(key)) {
                continue;
            }
            copy.put(key, AgentTraceSanitizer.isSensitiveKey(key)
                    ? "[REDACTED]"
                    : immutableValue(entry.getValue(), 0));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value, int depth) {
        if (depth >= MAX_NESTING_DEPTH) {
            return "[UNSUPPORTED]";
        }
        if (value == null || value instanceof Boolean || value instanceof Character || value instanceof Enum<?>) {
            return value;
        }
        if (value instanceof Number number) {
            return AgentTraceSanitizer.snapshotNumber(number);
        }
        if (value instanceof CharSequence sequence) {
            return AgentTraceSanitizer.boundedRedactedText(
                    sequence.toString(), AgentTraceSanitizer.MAX_INPUT_STRING_CHARS);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(Math.min(collection.size(), MAX_MAP_ENTRIES));
            for (Object item : collection) {
                if (copy.size() >= AgentTraceSanitizer.MAX_COLLECTION_ITEMS) {
                    break;
                }
                copy.add(immutableValue(item, depth + 1));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (copy.size() >= MAX_MAP_ENTRIES) {
                    break;
                }
                String key = AgentTraceSanitizer.boundedRedactedText(
                        String.valueOf(entry.getKey()), MAX_METADATA_CHARS);
                if (AgentTraceSanitizer.isInternalKey(key)) {
                    continue;
                }
                copy.put(key, AgentTraceSanitizer.isSensitiveKey(key)
                        ? "[REDACTED]"
                        : immutableValue(entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value.getClass().isArray()) {
            int size = Math.min(Array.getLength(value), AgentTraceSanitizer.MAX_COLLECTION_ITEMS);
            List<Object> copy = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                copy.add(immutableValue(Array.get(value, index), depth + 1));
            }
            return Collections.unmodifiableList(copy);
        }
        return "[UNSUPPORTED]";
    }

    private static List<String> immutableIds(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String bounded = AgentTraceSanitizer.boundedRedactedText(value, MAX_METADATA_CHARS);
            if (bounded.isBlank()) {
                continue;
            }
            unique.add(bounded);
            if (unique.size() >= MAX_SELECTED_IDS) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String boundedMetadata(String value) {
        return AgentTraceSanitizer.boundedRedactedText(value, MAX_METADATA_CHARS);
    }

    private static boolean isSha256(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!(current >= '0' && current <= '9')
                    && !(current >= 'a' && current <= 'f')
                    && !(current >= 'A' && current <= 'F')) {
                return false;
            }
        }
        return true;
    }
}
