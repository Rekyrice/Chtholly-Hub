package com.chtholly.agent.observability;

import com.chtholly.agent.ParamDef;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class AgentTraceSanitizerTest {

    @Test
    void sanitizedInputOnlyProjectsDeclaredPublicParameters() {
        Map<String, ParamDef> schema = Map.of(
                "query", new ParamDef("Search query", String.class, true),
                "_userQuestion", new ParamDef("Internal question", String.class, false),
                "_conversationHistory", new ParamDef("Internal history", String.class, false));
        Map<String, Object> input = Map.of(
                "query", "frieren",
                "_userQuestion", "private question",
                "_conversationHistory", "private history",
                "unknown", "private extra");

        Map<String, Object> sanitized = AgentTraceSanitizer.sanitizeInput(schema, input);

        assertThat(sanitized).containsOnly(Map.entry("query", "frieren"));
    }

    @Test
    void sensitiveKeysAreRedactedCaseInsensitively() {
        Map<String, ParamDef> schema = Map.of(
                "Authorization", new ParamDef("Authorization header", String.class, false),
                "accessToken", new ParamDef("Access token", String.class, false),
                "PASSWORD", new ParamDef("Password", String.class, false),
                "query", new ParamDef("Search query", String.class, false));

        Map<String, Object> sanitized = AgentTraceSanitizer.sanitizeInput(schema, Map.of(
                "Authorization", "Bearer top-secret",
                "accessToken", "token-value",
                "PASSWORD", "password-value",
                "query", "safe"));

        assertThat(sanitized).containsEntry("Authorization", "[REDACTED]");
        assertThat(sanitized).containsEntry("accessToken", "[REDACTED]");
        assertThat(sanitized).containsEntry("PASSWORD", "[REDACTED]");
        assertThat(sanitized).containsEntry("query", "safe");
    }

    @Test
    void sanitizedInputRedactsInfrastructureUrlUserInfoButPreservesOrdinaryUrls() {
        Map<String, ParamDef> schema = Map.of(
                "endpoint", new ParamDef("Database endpoint", String.class, true),
                "mirrors", new ParamDef("Database mirrors", List.class, false),
                "documentation", new ParamDef("Documentation URL", String.class, false));

        Map<String, Object> sanitized = AgentTraceSanitizer.sanitizeInput(schema, Map.of(
                "endpoint", "redis://default:redis-secret@cache.internal:6379/0",
                "mirrors", List.of(
                        "postgresql://db-user:pg-secret@db.internal/app",
                        "mongodb://mongo-user:mongo-secret@mongo.internal/app"),
                "documentation", "https://example.com/database/setup"));

        assertThat(sanitized)
                .containsEntry("endpoint", "redis://[REDACTED]@cache.internal:6379/0")
                .containsEntry("documentation", "https://example.com/database/setup");
        assertThat(sanitized.get("mirrors")).isEqualTo(List.of(
                "postgresql://[REDACTED]@db.internal/app",
                "mongodb://[REDACTED]@mongo.internal/app"));
        assertThat(sanitized.toString()).doesNotContain(
                "default", "redis-secret", "db-user", "pg-secret", "mongo-user", "mongo-secret");
    }

    @Test
    void standardSanitizationFindsUriUserInfoBeforeTruncatingOversizedInput() {
        String secret = "dsn-secret-" + "x".repeat(17_000);
        String dsn = "redis://service:" + secret + "@cache.internal:6379/0";
        Map<String, ParamDef> schema = Map.of(
                "endpoint", new ParamDef("Database endpoint", String.class, true));

        Map<String, Object> sanitized = AgentTraceSanitizer.sanitizeInput(
                schema, Map.of("endpoint", dsn));
        AgentTraceSanitizer.Preview preview = AgentTraceSanitizer.preview(
                "probe start " + dsn + " probe end");

        assertThat(sanitized)
                .containsEntry("endpoint", "redis://[REDACTED]@cache.internal:6379/0");
        assertThat(preview.text())
                .contains("probe start", "redis://[REDACTED]@cache.internal:6379/0", "probe end");
        assertThat(sanitized.toString()).doesNotContain("service", "dsn-secret");
        assertThat(preview.text()).doesNotContain("service", "dsn-secret");
    }

    @Test
    void scalarAndCollectionValuesAreBoundedWithoutRecursiveExpansion() {
        Map<String, ParamDef> schema = Map.of(
                "text", new ParamDef("Text", String.class, false),
                "count", new ParamDef("Count", Integer.class, false),
                "enabled", new ParamDef("Enabled", Boolean.class, false),
                "items", new ParamDef("Items", List.class, false),
                "complex", new ParamDef("Complex", Object.class, false));
        List<Object> items = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            items.add(index);
        }

        Map<String, Object> sanitized = AgentTraceSanitizer.sanitizeInput(schema, Map.of(
                "text", "x".repeat(300),
                "count", 7,
                "enabled", true,
                "items", items,
                "complex", Map.of("nested", "must-not-expand")));

        assertThat((String) sanitized.get("text")).hasSize(256);
        assertThat(sanitized).containsEntry("count", 7).containsEntry("enabled", true);
        assertThat((List<?>) sanitized.get("items")).hasSize(20);
        assertThat(sanitized.get("complex")).isEqualTo("[UNSUPPORTED]");
    }

    @Test
    void observationPreviewRedactsBeforeTruncatingButHashesOriginalText() {
        String prefix = "{\"Authorization\":\"Bearer abc123\",\"Cookie\":\"sid=cookie-secret\",";
        String keyValues = "token=token-secret password: password-secret safe=value ";
        String original = prefix + keyValues + "x".repeat(1_400) + "}";

        AgentTraceSanitizer.Preview preview = AgentTraceSanitizer.preview(original);

        assertThat(preview.text()).hasSizeLessThanOrEqualTo(1_200);
        assertThat(preview.text()).contains("[REDACTED]");
        assertThat(preview.text())
                .doesNotContain("abc123", "cookie-secret", "token-secret", "password-secret");
        assertThat(preview.sha256()).isEqualTo(sha256(original));
        assertThat(preview.chars()).isEqualTo(original.length());
        assertThat(preview.truncated()).isTrue();
    }

    @Test
    void observationPreviewRedactsQuotedInternalContextAndCompleteCookieHeaders() {
        String original = """
                {"_conversationHistory":"user said \\"private detail\\" yesterday","_userQuestion":"private question"}
                Cookie: sid=cookie-one; theme=cookie-two; session=cookie-three
                safe line remains
                """;

        AgentTraceSanitizer.Preview preview = AgentTraceSanitizer.preview(original);

        assertThat(preview.text()).contains("safe line remains");
        assertThat(preview.text()).doesNotContain(
                "private detail",
                "yesterday",
                "private question",
                "cookie-one",
                "cookie-two",
                "cookie-three");
    }

    @Test
    void observationPreviewRedactsUnclosedQuotedSecretsAndInternalContext() {
        String original = """
                {"token":"unterminated-secret
                {"_conversationHistory":"private-history
                safe line
                """;

        AgentTraceSanitizer.Preview preview = AgentTraceSanitizer.preview(original);

        assertThat(preview.text()).contains("safe line");
        assertThat(preview.text()).doesNotContain("unterminated-secret", "private-history");
    }

    @Test
    void observationPreviewRedactsBearerValuesAndCompleteQuotedAuthorizationHeaders() {
        String original = """
                token=Bearer bearer-secret
                Authorization: "Bearer first-secret", response=second-secret
                safe line
                """;

        AgentTraceSanitizer.Preview preview = AgentTraceSanitizer.preview(original);

        assertThat(preview.text()).contains("safe line");
        assertThat(preview.text()).doesNotContain("bearer-secret", "first-secret", "second-secret");
    }

    @Test
    void unrelatedLongObservationIsProcessedWithinABoundedTime() {
        assertTimeout(Duration.ofSeconds(2), () -> {
            AgentTraceSanitizer.Preview preview = AgentTraceSanitizer.preview("x".repeat(10_000));
            assertThat(preview.text()).hasSize(1_200);
            assertThat(preview.truncated()).isTrue();
        });
    }

    @Test
    void oversizedObservationCanShrinkBelowPreviewLimitAfterRedaction() {
        String original = "Cookie: sid=" + "secret".repeat(4_000);

        AgentTraceSanitizer.Preview preview = AgentTraceSanitizer.preview(original);

        assertThat(preview.text()).isEqualTo("Cookie: [REDACTED]");
        assertThat(preview.truncated()).isTrue();
        assertThat(preview.chars()).isEqualTo(original.length());
        assertThat(preview.sha256()).isEqualTo(sha256(original));
    }

    @Test
    void adminCapturePreservesUserContextAndUrlsButRedactsInfrastructureCredentials() {
        String source = "question=为什么 token 预算很小 tokenBudget=2048 https://example.com/post?id=7 "
                + "_userQuestion=保留这个问题 Authorization=Bearer admin-secret "
                + "access_token=access-secret password=pwd-secret\n"
                + "Set-Cookie=session=set-cookie-secret\n"
                + "Authorization-Header=Bearer header-secret\n"
                + "secret-key=provider-secret";

        AgentTraceSanitizer.ContentSnapshot snapshot =
                AgentTraceSanitizer.captureAdminContent(source, 131_072);

        assertThat(snapshot.text())
                .contains("为什么 token 预算很小", "tokenBudget=2048", "https://example.com/post?id=7",
                        "_userQuestion=保留这个问题")
                .doesNotContain("admin-secret", "access-secret", "pwd-secret",
                        "set-cookie-secret", "header-secret", "provider-secret");
        assertThat(snapshot.text()).contains("[REDACTED]");
        assertThat(snapshot.sourceChars()).isEqualTo(source.length());
        assertThat(snapshot.sha256()).isEqualTo(sha256(snapshot.text()));
        assertThat(snapshot.truncated()).isFalse();
        assertThat(snapshot.credentialRedacted()).isTrue();
    }

    @Test
    void adminCaptureHashesCredentialFilteredFullContentBeforeExplicitTruncation() {
        String source = "Cookie=session-secret\n" + "正文".repeat(100);

        AgentTraceSanitizer.ContentSnapshot snapshot =
                AgentTraceSanitizer.captureAdminContent(source, 12);
        AgentTraceSanitizer.ContentSnapshot complete =
                AgentTraceSanitizer.captureAdminContent(source, 131_072);

        assertThat(snapshot.text()).hasSize(12).doesNotContain("session-secret");
        assertThat(snapshot.sha256()).isEqualTo(complete.sha256());
        assertThat(snapshot.sourceChars()).isEqualTo(source.length());
        assertThat(snapshot.truncated()).isTrue();
        assertThat(snapshot.credentialRedacted()).isTrue();
    }

    @Test
    void adminCaptureRedactsJsonAuthorizationWithoutDiscardingSiblingFields() {
        String source = "{\"Authorization\":\"Bearer auth-secret\","
                + "\"safe\":\"keep this field\",\"answer\":\"done\"}";

        AgentTraceSanitizer.ContentSnapshot snapshot =
                AgentTraceSanitizer.captureAdminContent(source, 131_072);

        assertThat(snapshot.text())
                .contains("\"Authorization\":\"[REDACTED]\"", "\"safe\":\"keep this field\"", "\"answer\":\"done\"")
                .doesNotContain("auth-secret");
        assertThat(snapshot.credentialRedacted()).isTrue();
    }

    @Test
    void adminCaptureRedactsUrlUserInfoAndSignedQueryCredentials() {
        String source = "fetch https://alice:uri-secret@example.com/path?"
                + "X-Amz-Signature=signature-secret&AWSAccessKeyId=access-key-secret&topic=keep#section";

        AgentTraceSanitizer.ContentSnapshot snapshot =
                AgentTraceSanitizer.captureAdminContent(source, 131_072);

        assertThat(snapshot.text())
                .contains("https://[REDACTED]@example.com/path?", "topic=keep#section")
                .doesNotContain("alice", "uri-secret", "signature-secret", "access-key-secret");
        assertThat(snapshot.credentialRedacted()).isTrue();
    }

    @Test
    void adminCaptureRedactsInfrastructureUrlUserInfoAcrossSchemes() {
        String source = "redis://default:redis-secret@cache.internal:6379/0 "
                + "mongodb://db-user:mongo-secret@db.internal/app "
                + "amqps://broker:broker-secret@mq.internal/vhost";

        AgentTraceSanitizer.ContentSnapshot snapshot =
                AgentTraceSanitizer.captureAdminContent(source, 131_072);

        assertThat(snapshot.text())
                .contains(
                        "redis://[REDACTED]@cache.internal:6379/0",
                        "mongodb://[REDACTED]@db.internal/app",
                        "amqps://[REDACTED]@mq.internal/vhost")
                .doesNotContain(
                        "default", "redis-secret", "db-user", "mongo-secret",
                        "broker", "broker-secret");
        assertThat(snapshot.credentialRedacted()).isTrue();
    }

    @Test
    void standardPreviewRedactsInfrastructureUrlUserInfoWithoutDiscardingContext() {
        String source = "diagnostic start redis://default:redis-secret@cache.internal:6379/0 "
                + "postgresql://db-user:pg-secret@db.internal/app "
                + "mongodb://mongo-user:mongo-secret@mongo.internal/app diagnostic end";

        AgentTraceSanitizer.Preview preview = AgentTraceSanitizer.preview(source);

        assertThat(preview.text())
                .contains(
                        "diagnostic start",
                        "redis://[REDACTED]@cache.internal:6379/0",
                        "postgresql://[REDACTED]@db.internal/app",
                        "mongodb://[REDACTED]@mongo.internal/app",
                        "diagnostic end")
                .doesNotContain(
                        "default", "redis-secret", "db-user", "pg-secret",
                        "mongo-user", "mongo-secret");
    }

    @Test
    void standardDiagnosticsAreImmutableBoundedAndSafeByDefault() {
        List<Object> inputItems = new ArrayList<>(List.of("one", "two"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", "safe");
        input.put("items", inputItems);
        input.put("_conversationHistory", "private history");
        Map<String, ParamDef> schema = Map.of(
                "query", new ParamDef("Query", String.class, true),
                "items", new ParamDef("Items", List.class, false));

        AgentToolDiagnostics diagnostics = AgentToolDiagnostics.standard(
                        "search", schema, input, "token=private output")
                .withProvider("search-service")
                .withSourcePolicy("internal-index")
                .withResultCount(25);
        List<String> selectedIds = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            selectedIds.add("id-" + index);
        }
        diagnostics = diagnostics.withSelectedIds(selectedIds);
        inputItems.add("late mutation");
        selectedIds.clear();
        AgentToolDiagnostics captured = diagnostics;

        assertThat(captured.operation()).isEqualTo("search");
        assertThat(captured.provider()).isEqualTo("search-service");
        assertThat(captured.sourcePolicy()).isEqualTo("internal-index");
        assertThat(captured.sanitizedInput()).containsOnlyKeys("query", "items");
        assertThat((List<?>) captured.sanitizedInput().get("items"))
                .isEqualTo(List.of("one", "two"));
        assertThat(captured.outputPreview()).doesNotContain("private output");
        assertThat(captured.outputSha256()).isEqualTo(sha256("token=private output"));
        assertThat(captured.outputChars()).isEqualTo("token=private output".length());
        assertThat(captured.resultCount()).isEqualTo(25);
        assertThat(captured.selectedIds()).hasSize(20).contains("id-0", "id-19");
        assertThat(captured.errorCode()).isEmpty();
        assertThatThrownBy(() -> captured.sanitizedInput().put("late", "mutation"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) captured.sanitizedInput().get("items")).add("late"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> captured.selectedIds().add("late"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void diagnosticsDefensivelyCopyMapsAndArraysNestedInsideCollections() {
        Map<String, Object> nestedMap = new LinkedHashMap<>();
        nestedMap.put("value", "before");
        Object[] nestedArray = new Object[]{"before"};
        Map<String, Object> sanitizedInput = Map.of(
                "items", List.of(nestedMap, nestedArray));

        AgentToolDiagnostics diagnostics = new AgentToolDiagnostics(
                "search", "internal", "unspecified", sanitizedInput,
                "output", sha256("output"), 6, false, null, List.of(), "");
        nestedMap.put("value", "after");
        nestedArray[0] = "after";

        assertThat(diagnostics.sanitizedInput().toString()).contains("before").doesNotContain("after");
    }

    @Test
    void diagnosticsSnapshotMutableNumberImplementations() {
        AtomicInteger mutableNumber = new AtomicInteger(7);
        AgentToolDiagnostics diagnostics = new AgentToolDiagnostics(
                "search", "internal", "unspecified", Map.of("count", mutableNumber),
                "output", sha256("output"), 6, false, null, List.of(), "");

        mutableNumber.set(99);

        assertThat(diagnostics.sanitizedInput().get("count").toString()).isEqualTo("7");
    }

    @Test
    void publicDiagnosticsConstructorReappliesSafetyBounds() {
        AgentToolDiagnostics diagnostics = new AgentToolDiagnostics(
                "token=operation-secret" + "x".repeat(200),
                "provider" + "x".repeat(200),
                "source" + "x".repeat(200),
                Map.of(
                        "Authorization", "Bearer input-secret",
                        "_conversationHistory", "private history",
                        "query", "q".repeat(400)),
                "Cookie: sid=preview-secret",
                "not-a-hash-secret",
                10,
                false,
                1,
                List.of("id" + "x".repeat(300)),
                "error" + "x".repeat(300));

        assertThat(diagnostics.toString()).doesNotContain(
                "operation-secret", "input-secret", "private history", "preview-secret", "not-a-hash-secret");
        assertThat(diagnostics.operation()).hasSizeLessThanOrEqualTo(128);
        assertThat(diagnostics.provider()).hasSizeLessThanOrEqualTo(128);
        assertThat(diagnostics.sourcePolicy()).hasSizeLessThanOrEqualTo(128);
        assertThat(diagnostics.errorCode()).hasSizeLessThanOrEqualTo(128);
        assertThat(diagnostics.selectedIds().getFirst()).hasSizeLessThanOrEqualTo(128);
        assertThat((String) diagnostics.sanitizedInput().get("query")).hasSize(256);
        assertThat(diagnostics.sanitizedInput()).doesNotContainKey("_conversationHistory");
        assertThat(diagnostics.outputSha256()).isEmpty();
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
