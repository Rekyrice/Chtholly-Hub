package com.chtholly.agent.tools;

import com.chtholly.agent.ParamDef;
import com.chtholly.agent.observability.AgentToolDiagnostics;
import com.chtholly.agent.runtime.AgentToolExecutionException;
import com.chtholly.agent.runtime.AgentToolOutput;
import com.chtholly.agent.web.WebResearchException;
import com.chtholly.agent.web.WebSearchProvider;
import com.chtholly.agent.web.WebSearchResponse;
import com.chtholly.agent.web.WebSearchResult;
import com.chtholly.common.ratelimit.RateLimitResult;
import com.chtholly.common.ratelimit.RateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class WebSearchToolTest {

    private final WebSearchProvider provider = mock(WebSearchProvider.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSearchTool tool;

    @BeforeEach
    void setUp() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.permit());
        tool = new WebSearchTool(provider, rateLimiter, objectMapper);
    }

    @Test
    void declaresBoundedDiscoveryParameters() {
        Map<String, ParamDef> schema = tool.parameterSchema();

        assertThat(schema).containsOnlyKeys("query", "maxResults");
        assertThat(schema.get("query"))
                .extracting(ParamDef::type, ParamDef::required, ParamDef::minLength, ParamDef::maxLength)
                .containsExactly(String.class, true, 1, 200);
        assertThat(schema.get("maxResults"))
                .extracting(ParamDef::type, ParamDef::required, ParamDef::minimum, ParamDef::maximum)
                .containsExactly(Integer.class, false, 1L, 8L);
    }

    @Test
    void returnsStructuredDiscoveryWithoutCreatingEvidence() throws Exception {
        URI requestUrl = URI.create("https://html.duckduckgo.com/html/?q=%E4%BA%AC%E9%83%BD%E5%8A%A8%E7%94%BB+%E6%BC%94%E5%87%BA");
        URI finalUrl = URI.create("https://html.duckduckgo.com/html/?q=redirected");
        when(provider.searchDetailed("京都动画 演出", 3)).thenReturn(new WebSearchResponse(
                requestUrl,
                finalUrl,
                200,
                "text/html",
                1_234,
                List.of(requestUrl, finalUrl),
                List.of(
                        new WebSearchResult("First", "https://example.com/first", "one", 1),
                        new WebSearchResult("Second", "https://example.org/second", "two", 2))));

        AgentToolOutput output = tool.executeDetailed(
                Map.of("query", "  京都动画 演出  ", "maxResults", 3), 42L);

        JsonNode json = objectMapper.readTree(output.observation());
        assertThat(json.path("kind").asText()).isEqualTo("web_search_results");
        assertThat(json.path("discoveryOnly").asBoolean()).isTrue();
        assertThat(json.path("resultCount").asInt()).isEqualTo(2);
        assertThat(json.path("results").get(0).path("url").asText())
                .isEqualTo("https://example.com/first");
        assertThat(json.path("request").path("metadataAvailable").asBoolean()).isTrue();
        assertThat(json.path("request").path("finalUrl").asText()).isEqualTo(finalUrl.toString());
        assertThat(json.path("request").path("httpStatus").asInt()).isEqualTo(200);
        assertThat(json.path("request").path("contentType").asText()).isEqualTo("text/html");
        assertThat(json.path("request").path("bodyBytes").asInt()).isEqualTo(1_234);
        assertThat(json.path("nextAction").asText()).isEqualTo("web_fetch");
        assertThat(output.evidence()).isEmpty();
        verify(provider).searchDetailed("京都动画 演出", 3);

        AgentToolDiagnostics diagnostics = tool.traceDiagnostics(
                Map.of("query", "京都动画 演出", "maxResults", 3), output.observation());
        assertThat(diagnostics.operation()).isEqualTo("web_search");
        assertThat(diagnostics.provider()).isEqualTo("duckduckgo_html");
        assertThat(diagnostics.sourcePolicy()).isEqualTo("public_web_discovery_no_evidence");
        assertThat(diagnostics.resultCount()).isEqualTo(2);
        assertThat(diagnostics.selectedIds())
                .allMatch(id -> id.startsWith("web-result-"))
                .doesNotHaveDuplicates()
                .hasSize(2);
        assertThat(diagnostics.attributes())
                .containsEntry("requestedUrl", "https://html.duckduckgo.com/html/?q=%E4%BA%AC%E9%83%BD%E5%8A%A8%E7%94%BB+%E6%BC%94%E5%87%BA")
                .containsEntry("discoveryOnly", true)
                .containsEntry("evidenceProduced", false)
                .containsEntry("providerMetadataAvailable", true)
                .containsEntry("providerFinalUrl", finalUrl.toString())
                .containsEntry("httpStatus", 200)
                .containsEntry("contentType", "text/html")
                .containsEntry("responseBytes", 1_234);
        assertThat(diagnostics.attributes().get("redirectChain").toString())
                .contains(requestUrl.toString(), finalUrl.toString());
        assertThat(diagnostics.attributes().get("results").toString())
                .contains("First", "https://example.com/first");
    }

    @Test
    void appliesPerUserAndProviderLimits() {
        when(provider.searchDetailed("query", 5)).thenReturn(WebSearchResponse.resultsOnly(List.of(
                new WebSearchResult("Result", "https://example.com", "snippet", 1))));

        String observation = tool.execute(Map.of("query", "query"), 99L);
        AgentToolDiagnostics legacyDiagnostics = tool.traceDiagnostics(
                Map.of("query", "query"), observation);
        assertThat(legacyDiagnostics.attributes())
                .containsEntry("providerMetadataAvailable", false);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(rateLimiter, org.mockito.Mockito.times(2))
                .tryAcquire(keys.capture(), limits.capture(), org.mockito.ArgumentMatchers.eq(60));
        assertThat(keys.getAllValues().get(0)).contains("user", "99", "agent_web_search_user");
        assertThat(keys.getAllValues().get(1)).contains("identifier", "duckduckgo_html", "agent_web_search_provider");
        assertThat(limits.getAllValues()).containsExactly(10, 30);
    }

    @Test
    void translatesProviderAndRateLimiterFailuresToStableControlledErrors() {
        when(provider.searchDetailed("query", 5)).thenThrow(new WebResearchException(
                "WEB_PROVIDER_UNAVAILABLE", "provider unavailable"));

        assertThatThrownBy(() -> tool.execute(Map.of("query", "query"), 1L))
                .isInstanceOfSatisfying(AgentToolExecutionException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("WEB_PROVIDER_UNAVAILABLE");
                    assertThat(error.userMessage()).isEqualTo("provider unavailable");
                    assertThat(error.diagnosticAttributes())
                            .containsEntry("query", "query")
                            .containsEntry("providerHost", "html.duckduckgo.com")
                            .containsEntry("resultLimit", 5);
                });

        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("redis down"));
        assertThatThrownBy(() -> tool.execute(Map.of("query", "another"), 1L))
                .isInstanceOfSatisfying(AgentToolExecutionException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("WEB_RATE_LIMIT_UNAVAILABLE");
                    assertThat(error.diagnosticAttributes())
                            .containsEntry("query", "another")
                            .containsEntry("rateLimitKey", "agent:web_search:user");
                });
    }

    @Test
    void reportsAStableCodeWhenProviderQuotaIsExhausted() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.permit(), RateLimitResult.deny(17));

        assertThatThrownBy(() -> tool.execute(Map.of("query", "query"), 1L))
                .isInstanceOfSatisfying(AgentToolExecutionException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("WEB_PROVIDER_RATE_LIMITED");
                    assertThat(error.userMessage()).contains("17");
                });
    }

    @Test
    void rejectsAnExhaustedUserBeforeConsumingProviderQuotaAndKeepsFailureTraceContext() {
        reset(rateLimiter);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.deny(9));

        assertThatThrownBy(() -> tool.execute(Map.of("query", "agent trace"), 8L))
                .isInstanceOfSatisfying(AgentToolExecutionException.class, error ->
                        assertThat(error.errorCode()).isEqualTo("WEB_SEARCH_RATE_LIMITED"));
        verify(rateLimiter).tryAcquire(anyString(), org.mockito.ArgumentMatchers.eq(10),
                org.mockito.ArgumentMatchers.eq(60));
        verifyNoMoreInteractions(rateLimiter);

        AgentToolDiagnostics diagnostics = tool.traceDiagnostics(
                        Map.of("query", "agent trace"), "网页调研限流服务暂时不可用")
                .withErrorCode("WEB_RATE_LIMIT_UNAVAILABLE");
        assertThat(diagnostics.errorCode()).isEqualTo("WEB_RATE_LIMIT_UNAVAILABLE");
        assertThat(diagnostics.attributes())
                .containsEntry("query", "agent trace")
                .containsEntry("requestedUrl", "https://html.duckduckgo.com/html/?q=agent+trace")
                .containsEntry("providerHost", "html.duckduckgo.com");
        assertThat(diagnostics.attributes().get("safetyBoundary").toString())
                .contains("fixedProviderEndpoint");
    }
}
