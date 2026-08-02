package com.chtholly.agent.observability;

import com.chtholly.agent.ParamDef;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds bounded, redacted projections that are safe to persist in execution traces. */
public final class AgentTraceSanitizer {

    public static final int MAX_INPUT_STRING_CHARS = 256;
    public static final int MAX_COLLECTION_ITEMS = 20;
    public static final int MAX_OUTPUT_PREVIEW_CHARS = 1_200;
    private static final int MAX_REDACTION_INPUT_CHARS = 16_384;
    private static final int MAX_KEY_CHARS = 256;

    private static final Set<String> INTERNAL_KEYS = Set.of("_userquestion", "_conversationhistory");
    private static final List<String> SENSITIVE_KEY_FRAGMENTS = List.of(
            "authorization", "cookie", "token", "password", "secret");

    private AgentTraceSanitizer() {
    }

    /** Projects only declared public parameters and bounds every retained value. */
    public static Map<String, Object> sanitizeInput(
            Map<String, ParamDef> parameterSchema,
            Map<String, Object> input) {
        if (parameterSchema == null || parameterSchema.isEmpty() || input == null || input.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> projected = new LinkedHashMap<>();
        for (String key : parameterSchema.keySet()) {
            if (key == null || isInternalKey(key) || !input.containsKey(key)) {
                continue;
            }
            projected.put(key, isSensitiveKey(key) ? "[REDACTED]" : sanitizeValue(input.get(key)));
        }
        return Collections.unmodifiableMap(projected);
    }

    /** Creates a redacted preview while fingerprinting the complete original observation. */
    public static Preview preview(String observation) {
        String raw = observation == null ? "" : observation;
        int sourceLimit = Math.min(raw.length(), MAX_REDACTION_INPUT_CHARS);
        String redacted = redact(raw.substring(0, sourceLimit));
        boolean truncated = raw.length() > MAX_OUTPUT_PREVIEW_CHARS
                || raw.length() > sourceLimit
                || redacted.length() > MAX_OUTPUT_PREVIEW_CHARS;
        String text = redacted.length() > MAX_OUTPUT_PREVIEW_CHARS
                ? redacted.substring(0, MAX_OUTPUT_PREVIEW_CHARS)
                : redacted;
        return new Preview(text, sha256(raw), raw.length(), truncated);
    }

    static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEY_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    static boolean isInternalKey(String key) {
        return key != null && INTERNAL_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

    static String boundedRedactedText(String value, int maxChars) {
        String raw = value == null ? "" : value;
        String source = raw.substring(0, Math.min(raw.length(), MAX_REDACTION_INPUT_CHARS));
        return truncate(redact(source), Math.max(0, maxChars));
    }

    /** Produces a bounded, redacted message suitable for user-visible error templates. */
    public static String safeMessage(String value) {
        return boundedRedactedText(value, MAX_INPUT_STRING_CHARS);
    }

    private static Object sanitizeValue(Object value) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            return snapshotNumber(number);
        }
        if (value instanceof CharSequence sequence) {
            return truncate(sequence.toString(), MAX_INPUT_STRING_CHARS);
        }
        if (value instanceof Character || value instanceof Enum<?>) {
            return truncate(String.valueOf(value), MAX_INPUT_STRING_CHARS);
        }
        if (value instanceof Collection<?> collection) {
            return sanitizeCollection(collection);
        }
        if (value.getClass().isArray()) {
            List<Object> items = new ArrayList<>();
            int size = Math.min(Array.getLength(value), MAX_COLLECTION_ITEMS);
            for (int index = 0; index < size; index++) {
                items.add(sanitizeCollectionItem(Array.get(value, index)));
            }
            return Collections.unmodifiableList(items);
        }
        return "[UNSUPPORTED]";
    }

    private static List<Object> sanitizeCollection(Collection<?> values) {
        List<Object> items = new ArrayList<>();
        int index = 0;
        for (Object value : values) {
            if (index++ >= MAX_COLLECTION_ITEMS) {
                break;
            }
            items.add(sanitizeCollectionItem(value));
        }
        return Collections.unmodifiableList(items);
    }

    private static Object sanitizeCollectionItem(Object value) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            return snapshotNumber(number);
        }
        if (value instanceof CharSequence || value instanceof Character || value instanceof Enum<?>) {
            return truncate(String.valueOf(value), MAX_INPUT_STRING_CHARS);
        }
        return "[UNSUPPORTED]";
    }

    private static String redact(String text) {
        StringBuilder output = new StringBuilder(Math.min(text.length(), MAX_OUTPUT_PREVIEW_CHARS * 2));
        int index = 0;
        while (index < text.length()) {
            int urlEnd = urlEnd(text, index);
            if (urlEnd > index) {
                output.append("[URL]");
                index = urlEnd;
                continue;
            }

            Assignment assignment = assignmentAt(text, index);
            if (assignment == null
                    || (!isSensitiveKey(assignment.key()) && !isInternalKey(assignment.key()))) {
                output.append(text.charAt(index++));
                continue;
            }

            output.append(text, index, assignment.valueStart());
            index = redactAssignedValue(text, assignment, output);
        }
        return output.toString();
    }

    private static Assignment assignmentAt(String text, int start) {
        if (start > 0 && isKeyChar(text.charAt(start - 1))) {
            return null;
        }
        int cursor = start;
        char keyQuote = 0;
        if (isQuote(text.charAt(cursor))) {
            keyQuote = text.charAt(cursor++);
        }
        int keyStart = cursor;
        while (cursor < text.length() && isKeyChar(text.charAt(cursor))
                && cursor - keyStart <= MAX_KEY_CHARS) {
            cursor++;
        }
        if (cursor == keyStart || cursor - keyStart > MAX_KEY_CHARS) {
            return null;
        }
        int keyEnd = cursor;
        if (keyQuote != 0) {
            if (cursor >= text.length() || text.charAt(cursor) != keyQuote) {
                return null;
            }
            cursor++;
        }
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))
                && !isLineBreak(text.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= text.length() || (text.charAt(cursor) != ':' && text.charAt(cursor) != '=')) {
            return null;
        }
        cursor++;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))
                && !isLineBreak(text.charAt(cursor))) {
            cursor++;
        }
        return new Assignment(text.substring(keyStart, keyEnd), cursor);
    }

    private static int redactAssignedValue(String text, Assignment assignment, StringBuilder output) {
        int valueStart = assignment.valueStart();
        String key = assignment.key().toLowerCase(Locale.ROOT);
        if (isInternalKey(key) || key.contains("authorization") || key.contains("cookie")) {
            output.append("[REDACTED]");
            return lineEnd(text, valueStart);
        }
        if (valueStart < text.length() && isQuote(text.charAt(valueStart))) {
            char quote = text.charAt(valueStart);
            output.append(quote).append("[REDACTED]");
            int closingQuote = closingQuote(text, valueStart + 1, quote);
            if (closingQuote >= 0) {
                output.append(quote);
                return closingQuote + 1;
            }
            return lineEnd(text, valueStart + 1);
        }

        output.append("[REDACTED]");
        int cursor = valueStart;
        while (cursor < text.length()) {
            char current = text.charAt(cursor);
            if (isLineBreak(current) || current == ',' || current == ';'
                    || current == '&' || current == '}') {
                break;
            }
            cursor++;
        }
        return cursor;
    }

    private static int closingQuote(String text, int start, char quote) {
        int cursor = start;
        while (cursor < text.length() && !isLineBreak(text.charAt(cursor))) {
            char current = text.charAt(cursor);
            if (current == '\\') {
                cursor = Math.min(text.length(), cursor + 2);
                continue;
            }
            if (current == quote) {
                return cursor;
            }
            cursor++;
        }
        return -1;
    }

    private static int lineEnd(String text, int start) {
        int cursor = start;
        while (cursor < text.length() && !isLineBreak(text.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static int urlEnd(String text, int start) {
        boolean matches = text.regionMatches(true, start, "https://", 0, 8)
                || text.regionMatches(true, start, "http://", 0, 7);
        if (!matches) {
            return -1;
        }
        int cursor = start;
        while (cursor < text.length() && !Character.isWhitespace(text.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static boolean isKeyChar(char value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '_' || value == '-';
    }

    private static boolean isQuote(char value) {
        return value == '\"' || value == '\'';
    }

    private static boolean isLineBreak(char value) {
        return value == '\r' || value == '\n';
    }

    private static String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    static Object snapshotNumber(Number value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        return boundedRedactedText(String.valueOf(value), MAX_INPUT_STRING_CHARS);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Bounded observation projection plus a fingerprint of the complete source text. */
    public record Preview(String text, String sha256, int chars, boolean truncated) {
    }

    private record Assignment(String key, int valueStart) {
    }
}
