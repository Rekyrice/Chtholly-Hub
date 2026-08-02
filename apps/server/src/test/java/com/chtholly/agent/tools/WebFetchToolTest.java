package com.chtholly.agent.tools;

import com.chtholly.agent.ParamDef;
import com.chtholly.agent.evidence.Evidence;
import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.observability.AgentToolDiagnostics;
import com.chtholly.agent.runtime.AgentToolExecutionException;
import com.chtholly.agent.runtime.AgentToolOutput;
import com.chtholly.agent.web.ExtractedWebPage;
import com.chtholly.agent.web.RobotsDecision;
import com.chtholly.agent.web.RobotsPolicyResult;
import com.chtholly.agent.web.RobotsPolicyService;
import com.chtholly.agent.web.SafeWebHttpClient;
import com.chtholly.agent.web.SafeWebResponse;
import com.chtholly.agent.web.WebPageExtractor;
import com.chtholly.agent.web.WebResearchException;
import com.chtholly.agent.web.WebUrlPolicy;
import com.chtholly.common.ratelimit.RateLimitResult;
import com.chtholly.common.ratelimit.RateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class WebFetchToolTest {

    private final SafeWebHttpClient client = mock(SafeWebHttpClient.class);
    private final RobotsPolicyService robots = mock(RobotsPolicyService.class);
    private final WebPageExtractor extractor = mock(WebPageExtractor.class);
    private final WebUrlPolicy urlPolicy = mock(WebUrlPolicy.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebFetchTool tool;

    @BeforeEach
    void setUp() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.permit());
        tool = new WebFetchTool(client, robots, extractor, urlPolicy, rateLimiter, objectMapper);
    }

    @Test
    void declaresBoundedFetchParameters() {
        Map<String, ParamDef> schema = tool.parameterSchema();

        assertThat(schema).containsOnlyKeys("url", "maxChars");
        assertThat(schema.get("url"))
                .extracting(ParamDef::type, ParamDef::required, ParamDef::minLength, ParamDef::maxLength)
                .containsExactly(String.class, true, 1, 2_048);
        assertThat(schema.get("maxChars"))
                .extracting(ParamDef::type, ParamDef::required, ParamDef::minimum, ParamDef::maximum)
                .containsExactly(Integer.class, false, 1_000L, 12_000L);
    }

    @Test
    void checksRobotsFetchesExtractsAndCreatesVersionBoundEvidence() throws Exception {
        URI requested = URI.create("https://example.com/start");
        URI redirected = URI.create("https://www.example.com/article");
        String fullText = "x".repeat(1_500);
        SafeWebResponse response = new SafeWebResponse(
                redirected,
                200,
                List.of(requested, redirected),
                "text/html",
                "GBK",
                "<article>text</article>".getBytes(StandardCharsets.UTF_8));
        ExtractedWebPage page = new ExtractedWebPage(
                redirected,
                "Example article",
                fullText,
                "jsoup",
                2_400,
                fullText.length(),
                "a".repeat(64),
                "GBK",
                URI.create("https://declared.example/editorial-copy"));
        when(robots.check(requested)).thenReturn(new RobotsPolicyResult(
                RobotsDecision.ALLOW, false, "Allow: /", null));
        when(robots.check(redirected)).thenReturn(new RobotsPolicyResult(
                RobotsDecision.ALLOW, true, "Allow: /article", null));
        when(urlPolicy.validate(requested)).thenReturn(requested);
        whenClientGuardsAndReturns(requested, response, requested, redirected);
        when(extractor.extract(response)).thenReturn(page);

        AgentToolOutput output = tool.executeDetailed(
                Map.of("url", requested.toString(), "maxChars", 1_000), 42L);

        InOrder order = inOrder(robots, extractor);
        order.verify(robots).check(requested);
        order.verify(robots).check(redirected);
        order.verify(extractor).extract(response);
        verify(client).get(eq(requested), any());

        JsonNode json = objectMapper.readTree(output.observation());
        assertThat(json.path("kind").asText()).isEqualTo("web_fetched_page");
        assertThat(json.path("requestedUrl").asText()).isEqualTo(requested.toString());
        assertThat(json.path("finalUrl").asText()).isEqualTo(redirected.toString());
        assertThat(json.path("canonicalUrl").asText()).isEqualTo(redirected.toString());
        assertThat(json.path("declaredCanonicalUrl").asText())
                .isEqualTo("https://declared.example/editorial-copy");
        assertThat(json.path("canonicalResolution").path("declaredCanonicalAdopted").asBoolean())
                .isFalse();
        assertThat(json.path("page").path("text").asText()).hasSize(1_000);
        assertThat(json.path("extraction").path("truncatedByMaxChars").asBoolean()).isTrue();
        assertThat(json.path("robots").path("decision").asText()).isEqualTo("ALLOW");
        assertThat(json.path("robotsChecks")).hasSize(2);
        assertThat(json.path("robotsChecks").get(1).path("url").asText())
                .isEqualTo(redirected.toString());
        assertThat(json.path("http").path("status").asInt()).isEqualTo(200);
        assertThat(json.path("http").path("declaredCharset").asText()).isEqualTo("GBK");
        assertThat(json.path("http").path("effectiveCharset").asText()).isEqualTo("GBK");
        assertThat(json.path("http").path("charset").asText()).isEqualTo("GBK");
        assertThat(json.path("extraction").path("effectiveCharset").asText()).isEqualTo("GBK");
        assertThat(json.path("evidence").path("citationAssignment").asText())
                .isEqualTo("agent_loop");
        assertThat(json.path("evidence").path("citationId").isNull()).isTrue();
        assertThat(output.observation()).doesNotContain("\"citationId\":\"E1\"");

        assertThat(output.evidence()).hasSize(1);
        Evidence evidence = output.evidence().getFirst();
        assertThat(evidence.sourceType()).isEqualTo("WEB");
        assertThat(evidence.documentId()).isEqualTo(redirected.toString());
        assertThat(evidence.sourceHash()).isEqualTo("a".repeat(64));
        assertThat(evidence.excerpt()).hasSize(1_000);
        assertThat(evidence.permissions()).containsExactly("PUBLIC");
        Evidence existing = Evidence.fromWebPage(
                "https://existing.example/article", "Existing", "b".repeat(64), "existing");
        EvidenceSet merged = EvidenceSet.of(List.of(existing), Set.of("PUBLIC"))
                .append(output.evidence());
        assertThat(merged.items()).extracting(Evidence::citationId).containsExactly("E1", "E2");
        assertThat(merged.items().get(1).evidenceId()).isEqualTo(evidence.evidenceId());

        AgentToolDiagnostics diagnostics = tool.traceDiagnostics(
                Map.of("url", requested.toString(), "maxChars", 1_000), output.observation());
        assertThat(diagnostics.operation()).isEqualTo("web_fetch");
        assertThat(diagnostics.provider()).isEqualTo("www.example.com");
        assertThat(diagnostics.sourcePolicy()).isEqualTo("robots_checked_public_http");
        assertThat(diagnostics.resultCount()).isEqualTo(1);
        assertThat(diagnostics.selectedIds()).containsExactly(evidence.evidenceId());
        assertThat(diagnostics.attributes())
                .containsEntry("requestedUrl", requested.toString())
                .containsEntry("finalUrl", redirected.toString())
                .containsEntry("httpStatus", 200)
                .containsEntry("contentType", "text/html")
                .containsEntry("declaredCharset", "GBK")
                .containsEntry("effectiveCharset", "GBK")
                .containsEntry("charset", "GBK")
                .containsEntry("extractor", "jsoup")
                .containsEntry("contentSha256", "a".repeat(64));
        assertThat(diagnostics.attributes().get("redirectChain").toString())
                .contains(requested.toString(), redirected.toString());
        assertThat(diagnostics.attributes().get("robots").toString())
                .contains("ALLOW", "Allow: /");
        assertThat(diagnostics.attributes().get("robotsChecks").toString())
                .contains(requested.toString(), redirected.toString(), "Allow: /article");
        assertThat(diagnostics.attributes().get("safetyBoundary").toString())
                .contains("all_public_dns_answers", "redirect_revalidation");

        ArgumentCaptor<String> quotaKeys = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, org.mockito.Mockito.times(3))
                .tryAcquire(quotaKeys.capture(), anyInt(), org.mockito.ArgumentMatchers.eq(60));
        assertThat(quotaKeys.getAllValues().get(0)).contains("user", "42", "agent_web_fetch_user");
        assertThat(quotaKeys.getAllValues().get(1)).contains("identifier", "example.com", "agent_web_fetch_host");
        assertThat(quotaKeys.getAllValues().get(2)).contains("identifier", "www.example.com", "agent_web_fetch_host");
    }

    @Test
    void appliesPerUserAndHostLimits() {
        URI requested = URI.create("https://example.org/article");
        when(urlPolicy.validate(requested)).thenReturn(requested);
        when(robots.check(requested)).thenReturn(new RobotsPolicyResult(
                RobotsDecision.DENY, false, "Disallow: /article", null));
        whenClientGuardsAndReturns(requested, null, requested);

        assertThatThrownBy(() -> tool.execute(Map.of("url", requested.toString()), 77L))
                .isInstanceOf(AgentToolExecutionException.class);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(rateLimiter, org.mockito.Mockito.times(2))
                .tryAcquire(keys.capture(), limits.capture(), org.mockito.ArgumentMatchers.eq(60));
        assertThat(keys.getAllValues().get(0)).contains("user", "77", "agent_web_fetch_user");
        assertThat(keys.getAllValues().get(1)).contains("identifier", "example.org", "agent_web_fetch_host");
        assertThat(limits.getAllValues()).containsExactly(20, 30);
    }

    @Test
    void failsClosedForRobotsAndTranslatesWebBoundaryErrors() {
        URI requested = URI.create("https://example.com/private");
        when(urlPolicy.validate(requested)).thenReturn(requested);
        when(robots.check(requested)).thenReturn(new RobotsPolicyResult(
                RobotsDecision.DENY, true, null, "WEB_ROBOTS_FETCH_FAILED"));
        whenClientGuardsAndReturns(requested, null, requested);

        assertThatThrownBy(() -> tool.execute(Map.of("url", requested.toString()), 1L))
                .isInstanceOfSatisfying(AgentToolExecutionException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("WEB_ROBOTS_FETCH_FAILED");
                    assertThat(error.diagnosticAttributes())
                            .containsEntry("requestedUrl", requested.toString())
                            .containsEntry("providerHost", "example.com");
                    assertThat(error.diagnosticAttributes().get("robots").toString())
                            .contains("DENY", "WEB_ROBOTS_FETCH_FAILED");
                });

        when(robots.check(requested)).thenReturn(new RobotsPolicyResult(
                RobotsDecision.ALLOW, true, null, null));
        reset(client);
        whenClientGuardsThenThrows(
                requested,
                new WebResearchException("WEB_URL_ADDRESS_FORBIDDEN", "private network"),
                requested);
        assertThatThrownBy(() -> tool.execute(Map.of("url", requested.toString()), 1L))
                .isInstanceOfSatisfying(AgentToolExecutionException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("WEB_URL_ADDRESS_FORBIDDEN");
                    assertThat(error.userMessage()).isEqualTo("private network");
                });
    }

    @Test
    void rejectsHttpErrorsAndRateLimiterFailuresWithStableCodes() {
        URI requested = URI.create("https://example.com/missing");
        when(urlPolicy.validate(requested)).thenReturn(requested);
        when(robots.check(requested)).thenReturn(new RobotsPolicyResult(
                RobotsDecision.ALLOW, false, null, null));
        whenClientGuardsAndReturns(requested, new SafeWebResponse(
                requested, 404, List.of(requested), "text/html", new byte[0]), requested);

        assertThatThrownBy(() -> tool.execute(Map.of("url", requested.toString()), 1L))
                .isInstanceOfSatisfying(AgentToolExecutionException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("WEB_FETCH_HTTP_ERROR");
                    assertThat(error.diagnosticAttributes())
                            .containsEntry("requestedUrl", requested.toString())
                            .containsEntry("finalUrl", requested.toString())
                            .containsEntry("httpStatus", 404)
                            .containsEntry("contentType", "text/html")
                            .containsEntry("responseBytes", 0);
                    assertThat(error.diagnosticAttributes().get("redirectChain").toString())
                            .contains(requested.toString());
                });

        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("redis down"));
        assertThatThrownBy(() -> tool.execute(Map.of("url", requested.toString()), 1L))
                .isInstanceOfSatisfying(AgentToolExecutionException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("WEB_RATE_LIMIT_UNAVAILABLE");
                    assertThat(error.diagnosticAttributes())
                            .containsEntry("requestedUrl", requested.toString())
                            .containsEntry("rateLimitKey", "agent:web_fetch:user");
                });
    }

    @Test
    void rejectsAnExhaustedUserBeforeConsumingHostQuotaAndKeepsFailureTraceContext() {
        URI requested = URI.create("https://example.com/article");
        when(urlPolicy.validate(requested)).thenReturn(requested);
        reset(rateLimiter);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.deny(11));

        assertThatThrownBy(() -> tool.execute(Map.of("url", requested.toString()), 5L))
                .isInstanceOfSatisfying(AgentToolExecutionException.class, error ->
                        assertThat(error.errorCode()).isEqualTo("WEB_FETCH_RATE_LIMITED"));
        verify(rateLimiter).tryAcquire(anyString(), org.mockito.ArgumentMatchers.eq(20),
                org.mockito.ArgumentMatchers.eq(60));
        verifyNoMoreInteractions(rateLimiter);

        AgentToolDiagnostics diagnostics = tool.traceDiagnostics(
                        Map.of("url", requested.toString()), "网页调研限流服务暂时不可用")
                .withErrorCode("WEB_RATE_LIMIT_UNAVAILABLE");
        assertThat(diagnostics.errorCode()).isEqualTo("WEB_RATE_LIMIT_UNAVAILABLE");
        assertThat(diagnostics.provider()).isEqualTo("example.com");
        assertThat(diagnostics.attributes())
                .containsEntry("requestedUrl", requested.toString())
                .containsEntry("maxChars", 8_000);
        assertThat(diagnostics.attributes().get("safetyBoundary").toString())
                .contains("all_public_dns_answers", "redirect_revalidation");

        AgentToolDiagnostics credentialUrl = tool.traceDiagnostics(
                Map.of("url", "https://alice:private@example.com/article"), "网页地址格式无效");
        assertThat(credentialUrl.attributes().get("requestedUrl"))
                .isEqualTo("https://example.com/article");
        assertThat(credentialUrl.attributes().toString()).doesNotContain("alice", "private");
    }

    @Test
    void deniesACrossOriginRedirectBeforeTheRedirectTargetRequest() {
        URI requested = URI.create("https://example.com/start");
        URI redirected = URI.create("https://blocked.example.net/private");
        when(urlPolicy.validate(requested)).thenReturn(requested);
        when(robots.check(requested)).thenReturn(new RobotsPolicyResult(
                RobotsDecision.ALLOW, false, "Allow: /", null));
        when(robots.check(redirected)).thenReturn(new RobotsPolicyResult(
                RobotsDecision.DENY, false, "Disallow: /private", null));
        whenClientGuardsAndReturns(requested, null, requested, redirected);

        assertThatThrownBy(() -> tool.execute(Map.of("url", requested.toString()), 9L))
                .isInstanceOfSatisfying(AgentToolExecutionException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("WEB_ROBOTS_DENIED");
                    assertThat(error.diagnosticAttributes())
                            .containsEntry("currentHopUrl", redirected.toString())
                            .containsEntry("currentHopHost", "blocked.example.net")
                            .containsEntry("failureStage", "hop_robots_denied");
                    assertThat(error.diagnosticAttributes().get("robotsChecks").toString())
                            .contains(requested.toString(), redirected.toString(), "Disallow: /private");
                });
        verify(robots).check(requested);
        verify(robots).check(redirected);
        verify(extractor, never()).extract(any());

        ArgumentCaptor<String> quotaKeys = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, org.mockito.Mockito.times(3))
                .tryAcquire(quotaKeys.capture(), anyInt(), org.mockito.ArgumentMatchers.eq(60));
        assertThat(quotaKeys.getAllValues().get(0)).contains("user", "9", "agent_web_fetch_user");
        assertThat(quotaKeys.getAllValues().get(1)).contains("identifier", "example.com");
        assertThat(quotaKeys.getAllValues().get(2)).contains("identifier", "blocked.example.net");
    }

    private void whenClientGuardsAndReturns(
            URI requested,
            SafeWebResponse response,
            URI... guardedTargets) {
        when(client.get(eq(requested), org.mockito.ArgumentMatchers.<Consumer<URI>>any()))
                .thenAnswer(invocation -> {
                    Consumer<URI> guard = invocation.getArgument(1);
                    for (URI target : guardedTargets) {
                        guard.accept(target);
                    }
                    return response;
                });
    }

    private void whenClientGuardsThenThrows(
            URI requested,
            RuntimeException failure,
            URI... guardedTargets) {
        when(client.get(eq(requested), org.mockito.ArgumentMatchers.<Consumer<URI>>any()))
                .thenAnswer(invocation -> {
                    Consumer<URI> guard = invocation.getArgument(1);
                    for (URI target : guardedTargets) {
                        guard.accept(target);
                    }
                    throw failure;
                });
    }
}
