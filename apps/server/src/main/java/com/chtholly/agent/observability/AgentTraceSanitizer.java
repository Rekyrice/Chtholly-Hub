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
    public static final int MAX_ADMIN_CONTENT_CHARS = 131_072;
    public static final int MAX_ADMIN_TURN_CAPTURE_CHARS = 2_097_152;
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
        String redacted = redactStandardPreview(raw);
        boolean truncated = raw.length() > MAX_OUTPUT_PREVIEW_CHARS
                || redacted.length() > MAX_OUTPUT_PREVIEW_CHARS;
        String text = redacted.length() > MAX_OUTPUT_PREVIEW_CHARS
                ? redacted.substring(0, MAX_OUTPUT_PREVIEW_CHARS)
                : redacted;
        return new Preview(text, sha256(raw), raw.length(), truncated);
    }

    /**
     * Captures administrator-only diagnostic content while removing infrastructure credentials.
     * Ordinary questions, page context, internal agent fields, and URLs remain replayable.
     */
    public static ContentSnapshot captureAdminContent(String value, int maxChars) {
        return captureAdmin(value, maxChars).snapshot();
    }

    static AdminCapture captureAdmin(String value, int maxChars) {
        String raw = value == null ? "" : value;
        CredentialRedaction filtered = redactCredentials(raw);
        int limit = Math.max(0, Math.min(maxChars, MAX_ADMIN_CONTENT_CHARS));
        String text = filtered.text().substring(0, Math.min(filtered.text().length(), limit));
        boolean truncated = raw.length() > limit || filtered.text().length() > limit;
        return new AdminCapture(
                new ContentSnapshot(
                        text,
                        raw.length(),
                        sha256(filtered.text()),
                        truncated,
                        filtered.redactions() > 0),
                filtered.redactions());
    }

    static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEY_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    /** Returns whether an administrator trace field is an infrastructure credential. */
    public static boolean isAdminCredentialKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.equals("authorization")
                || normalized.equals("authorizationheader")
                || normalized.equals("proxyauthorization")
                || normalized.equals("cookie")
                || normalized.equals("cookieheader")
                || normalized.equals("setcookie")
                || normalized.equals("token")
                || normalized.equals("apikey")
                || normalized.equals("accesskey")
                || normalized.endsWith("password")
                || normalized.endsWith("secret")
                || normalized.endsWith("credential")
                || normalized.endsWith("token")
                || normalized.endsWith("apikey")
                || normalized.endsWith("accesskey")
                || normalized.endsWith("accesskeyid")
                || normalized.endsWith("accessid")
                || normalized.endsWith("secretkey")
                || normalized.endsWith("privatekey")
                || normalized.endsWith("signature")
                || normalized.equals("sig")
                || normalized.endsWith("keypairid");
    }

    static boolean isInternalKey(String key) {
        return key != null && INTERNAL_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

    static String boundedRedactedText(String value, int maxChars) {
        String raw = value == null ? "" : value;
        return truncate(redactStandardPreview(raw), Math.max(0, maxChars));
    }

    static String boundedSanitizedInputText(String value, int maxChars) {
        String raw = value == null ? "" : value;
        String redacted = redactCredentials(redact(raw, false)).text();
        return truncate(redacted, Math.max(0, maxChars));
    }

    /** Preserves ordinary URLs while removing credentials from a bounded tool-input value. */
    public static String safeSanitizedInputText(String value) {
        return boundedSanitizedInputText(value, MAX_INPUT_STRING_CHARS);
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
            return boundedSanitizedInputText(sequence.toString(), MAX_INPUT_STRING_CHARS);
        }
        if (value instanceof Character || value instanceof Enum<?>) {
            return boundedSanitizedInputText(String.valueOf(value), MAX_INPUT_STRING_CHARS);
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
            return boundedSanitizedInputText(String.valueOf(value), MAX_INPUT_STRING_CHARS);
        }
        return "[UNSUPPORTED]";
    }

    private static String redact(String text) {
        return redact(text, true);
    }

    private static String redact(String text, boolean redactUrls) {
        StringBuilder output = new StringBuilder(Math.min(text.length(), MAX_OUTPUT_PREVIEW_CHARS * 2));
        int index = 0;
        while (index < text.length()) {
            if (redactUrls) {
                int urlEnd = urlEnd(text, index);
                if (urlEnd > index) {
                    output.append("[URL]");
                    index = urlEnd;
                    continue;
                }
            }

            Assignment assignment = assignmentAt(text, index);
            if (assignment == null
                    || (!isSensitiveKey(assignment.key()) && !isInternalKey(assignment.key()))) {
                output.append(text.charAt(index++));
                continue;
            }

            output.append(text, index, assignment.valueStart());
            index = redactAssignedValue(text, assignment, output, true);
        }
        return output.toString();
    }

    private static String redactStandardPreview(String text) {
        return redactCredentials(redact(text)).text();
    }

    private static CredentialRedaction redactCredentials(String text) {
        StringBuilder output = new StringBuilder(text.length());
        int index = 0;
        int redactions = 0;
        while (index < text.length()) {
            UrlUserInfo userInfo = urlUserInfoAt(text, index);
            if (userInfo != null) {
                output.append(text, index, userInfo.credentialsStart()).append("[REDACTED]@");
                index = userInfo.atIndex() + 1;
                redactions++;
                continue;
            }
            Assignment assignment = assignmentAt(text, index);
            if (assignment == null || !isAdminCredentialKey(assignment.key())) {
                output.append(text.charAt(index++));
                continue;
            }
            output.append(text, index, assignment.valueStart());
            index = redactAssignedValue(text, assignment, output, false);
            redactions++;
        }
        return new CredentialRedaction(output.toString(), redactions);
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

    private static int redactAssignedValue(
            String text,
            Assignment assignment,
            StringBuilder output,
            boolean redactCompleteHeaderLine) {
        int valueStart = assignment.valueStart();
        String key = assignment.key().toLowerCase(Locale.ROOT);
        if (redactCompleteHeaderLine
                && (isInternalKey(key) || key.contains("authorization") || key.contains("cookie"))) {
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

        if (isInternalKey(key) || key.contains("cookie")) {
            output.append("[REDACTED]");
            return lineEnd(text, valueStart);
        }

        output.append("[REDACTED]");
        return credentialValueEnd(text, valueStart);
    }

    private static int credentialValueEnd(String text, int valueStart) {
        int cursor = valueStart;
        while (cursor < text.length()) {
            char current = text.charAt(cursor);
            if (isLineBreak(current) || current == ',' || current == ';'
                    || current == '&' || current == '}') {
                break;
            }
            if (Character.isWhitespace(current)) {
                int next = cursor + 1;
                while (next < text.length() && Character.isWhitespace(text.charAt(next))
                        && !isLineBreak(text.charAt(next))) {
                    next++;
                }
                if (next < text.length() && assignmentAt(text, next) != null) {
                    break;
                }
            }
            cursor++;
        }
        return cursor;
    }

    private static UrlUserInfo urlUserInfoAt(String text, int start) {
        if (start >= text.length() || !isAsciiAlpha(text.charAt(start))) {
            return null;
        }
        int cursor = start + 1;
        int maxSchemeEnd = Math.min(text.length(), start + 64);
        while (cursor < maxSchemeEnd && isSchemeChar(text.charAt(cursor))) {
            cursor++;
        }
        if (cursor + 2 >= text.length()
                || text.charAt(cursor) != ':'
                || text.charAt(cursor + 1) != '/'
                || text.charAt(cursor + 2) != '/') {
            return null;
        }
        int authorityStart = cursor + 3;
        int lastAt = -1;
        for (cursor = authorityStart; cursor < text.length(); cursor++) {
            char current = text.charAt(cursor);
            if (current == '@') {
                lastAt = cursor;
                continue;
            }
            if (current == '/' || current == '?' || current == '#'
                    || Character.isWhitespace(current) || current == '"' || current == '\''
                    || current == '}' || current == ']') {
                break;
            }
        }
        return lastAt > authorityStart ? new UrlUserInfo(authorityStart, lastAt) : null;
    }

    private static boolean isAsciiAlpha(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z';
    }

    private static boolean isSchemeChar(char value) {
        return isAsciiAlpha(value)
                || value >= '0' && value <= '9'
                || value == '+' || value == '-' || value == '.';
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

    /** Replayable administrator content with explicit provenance and truncation state. */
    public record ContentSnapshot(
            String text,
            int sourceChars,
            String sha256,
            boolean truncated,
            boolean credentialRedacted) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("text", text);
            map.put("sourceChars", sourceChars);
            map.put("sha256", sha256);
            map.put("truncated", truncated);
            map.put("credentialRedacted", credentialRedacted);
            return Collections.unmodifiableMap(map);
        }
    }

    record AdminCapture(ContentSnapshot snapshot, int redactions) {
    }

    private record CredentialRedaction(String text, int redactions) {
    }

    private record Assignment(String key, int valueStart) {
    }

    private record UrlUserInfo(int credentialsStart, int atIndex) {
    }
}
