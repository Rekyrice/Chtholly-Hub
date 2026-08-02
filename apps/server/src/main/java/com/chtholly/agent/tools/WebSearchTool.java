package com.chtholly.agent.tools;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.observability.AgentToolDiagnostics;
import com.chtholly.agent.runtime.AgentToolExecutionException;
import com.chtholly.agent.runtime.AgentToolOutput;
import com.chtholly.agent.web.WebResearchException;
import com.chtholly.agent.web.WebSearchProvider;
import com.chtholly.agent.web.WebSearchResponse;
import com.chtholly.agent.web.WebSearchResult;
import com.chtholly.common.ratelimit.RateLimiter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Public-web discovery tool backed by DuckDuckGo's non-JavaScript HTML results. */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class WebSearchTool implements AgentTool {

    private static final String PROVIDER = "duckduckgo_html";
    private static final String ENDPOINT = "https://html.duckduckgo.com/html/?q=";
    private static final int DEFAULT_RESULTS = 5;
    private static final int MAX_RESULTS = 8;
    private static final int MAX_TITLE_CHARS = 300;
    private static final int MAX_SNIPPET_CHARS = 1_000;

    private final WebSearchProvider provider;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    /**
     * Creates a bounded public-web discovery tool.
     *
     * @param provider public search provider
     * @param rateLimiter shared Redis-backed rate limiter
     * @param objectMapper JSON codec used for immutable observation envelopes
     */
    public WebSearchTool(
            WebSearchProvider provider,
            RateLimiter rateLimiter,
            ObjectMapper objectMapper) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "搜索公开网页并返回候选标题、URL 与摘要；结果仅用于发现来源，回答事实前必须再调用 web_fetch。";
    }

    @Override
    public Map<String, ParamDef> parameterSchema() {
        return Map.of(
                "query", ParamDef.string("网页搜索关键词", true, 1, 200),
                "maxResults", ParamDef.integer("最多返回的候选结果数", false, 1, MAX_RESULTS));
    }

    @Override
    public String execute(Map<String, Object> input, long userId) {
        String query = requiredQuery(input);
        int limit = boundedInteger(input.get("maxResults"), DEFAULT_RESULTS, 1, MAX_RESULTS);
        Map<String, Object> executionContext = searchExecutionContext(query, limit);
        try {
            WebResearchRateLimits.acquireSearch(rateLimiter, userId);
        } catch (AgentToolExecutionException exception) {
            throw augment(exception, executionContext);
        }
        try {
            WebSearchResponse providerResponse = provider.searchDetailed(query, limit);
            if (providerResponse == null) {
                throw new WebResearchException(
                        "WEB_PROVIDER_INVALID_RESPONSE",
                        "网页搜索服务返回了无效结果。");
            }
            List<Map<String, Object>> results = normalizeResults(providerResponse.results(), limit);
            boolean metadataAvailable = providerMetadataAvailable(providerResponse);
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("url", providerResponse.requestUrl() == null
                    ? requestUrl(query) : providerResponse.requestUrl().toString());
            request.put("requestedUrl", providerResponse.requestUrl() == null
                    ? requestUrl(query) : providerResponse.requestUrl().toString());
            request.put("finalUrl", providerResponse.finalUrl() == null
                    ? null : providerResponse.finalUrl().toString());
            request.put("host", "html.duckduckgo.com");
            request.put("resultLimit", limit);
            request.put("metadataAvailable", metadataAvailable);
            request.put("httpStatus", providerResponse.statusCode());
            request.put("contentType", providerResponse.contentType());
            request.put("bodyBytes", providerResponse.bodyBytes());
            request.put("redirectChain", providerResponse.redirectChain().stream()
                    .map(URI::toString).toList());

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("kind", "web_search_results");
            envelope.put("query", query);
            envelope.put("provider", PROVIDER);
            envelope.put("discoveryOnly", true);
            envelope.put("evidenceProduced", false);
            envelope.put("request", request);
            envelope.put("resultCount", results.size());
            envelope.put("results", results);
            envelope.put("nextAction", "web_fetch");
            return writeObservation(envelope);
        } catch (WebResearchException exception) {
            throw controlled(exception, executionContext);
        }
    }

    @Override
    public AgentToolOutput executeDetailed(Map<String, Object> input, long userId) {
        return new AgentToolOutput(execute(input, userId), List.of());
    }

    @Override
    public AgentToolDiagnostics traceDiagnostics(Map<String, Object> input, String observation) {
        AgentToolDiagnostics base = AgentTool.super.traceDiagnostics(input, observation)
                .withProvider(PROVIDER)
                .withSourcePolicy("public_web_discovery_no_evidence");
        Map<String, Object> attributes = new LinkedHashMap<>();
        String query = inputText(input, "query");
        if (!query.isBlank()) {
            attributes.put("query", query);
            attributes.put("requestedUrl", requestUrl(query));
        }
        attributes.put("providerHost", "html.duckduckgo.com");
        attributes.put("discoveryOnly", true);
        attributes.put("evidenceProduced", false);
        attributes.put("nextAction", "web_fetch");
        attributes.put("providerMetadataAvailable", false);
        attributes.put("safetyBoundary", Map.of(
                "fixedProviderEndpoint", true,
                "responseBodyBounded", true,
                "resultUrlsRequireFetchBeforeUse", true));

        JsonNode root = readObservation(observation);
        Integer resultCount = null;
        List<String> selectedUrls = new ArrayList<>();
        if (root != null && "web_search_results".equals(root.path("kind").asText())) {
            resultCount = Math.max(0, root.path("resultCount").asInt(0));
            JsonNode request = root.path("request");
            copyText(attributes, "requestedUrl", request.path("url"));
            copyText(attributes, "providerFinalUrl", request.path("finalUrl"));
            copyNumber(attributes, "resultLimit", request.path("resultLimit"));
            copyBoolean(attributes, "providerMetadataAvailable", request.path("metadataAvailable"));
            copyNumber(attributes, "httpStatus", request.path("httpStatus"));
            copyText(attributes, "contentType", request.path("contentType"));
            copyNumber(attributes, "responseBytes", request.path("bodyBytes"));
            attributes.put("redirectChain", textList(request.path("redirectChain")));
            List<Map<String, Object>> results = new ArrayList<>();
            for (JsonNode result : root.path("results")) {
                String url = result.path("url").asText("");
                if (!url.isBlank()) {
                    selectedUrls.add("web-result-" + sha256(url).substring(0, 16));
                }
                Map<String, Object> item = new LinkedHashMap<>();
                copyNumber(item, "rank", result.path("rank"));
                copyText(item, "title", result.path("title"));
                copyText(item, "url", result.path("url"));
                copyText(item, "snippet", result.path("snippet"));
                results.add(item);
            }
            attributes.put("results", results);
        }
        return base.withResultCount(resultCount)
                .withSelectedIds(selectedUrls)
                .withAttributes(attributes);
    }

    private List<Map<String, Object>> normalizeResults(List<WebSearchResult> rawResults, int limit) {
        if (rawResults == null || rawResults.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (WebSearchResult result : rawResults) {
            if (result == null || blank(result.title()) || blank(result.url())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", Math.max(1, result.rank()));
            item.put("title", bounded(result.title().strip(), MAX_TITLE_CHARS));
            item.put("url", bounded(result.url().strip(), 2_048));
            item.put("snippet", bounded(result.snippet() == null ? "" : result.snippet().strip(), MAX_SNIPPET_CHARS));
            results.add(item);
            if (results.size() >= limit) {
                break;
            }
        }
        return List.copyOf(results);
    }

    private String writeObservation(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new AgentToolExecutionException(
                    "WEB_OBSERVATION_ENCODING_FAILED",
                    "网页搜索结果暂时无法整理，请稍后重试。",
                    Map.of(),
                    exception);
        }
    }

    private JsonNode readObservation(String observation) {
        if (observation == null || observation.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(observation);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static String requiredQuery(Map<String, Object> input) {
        String query = inputText(input, "query").strip();
        if (query.isBlank() || query.length() > 200) {
            throw new AgentToolExecutionException(
                    "WEB_SEARCH_QUERY_INVALID",
                    "网页搜索关键词不能为空，且长度不能超过 200 个字符。");
        }
        return query;
    }

    private static String requestUrl(String query) {
        return ENDPOINT + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8);
    }

    private static int boundedInteger(Object value, int fallback, int minimum, int maximum) {
        if (!(value instanceof Number number)) {
            return fallback;
        }
        return Math.max(minimum, Math.min(maximum, number.intValue()));
    }

    private static String inputText(Map<String, Object> input, String key) {
        if (input == null || input.get(key) == null) {
            return "";
        }
        return String.valueOf(input.get(key));
    }

    private static void copyText(Map<String, Object> target, String key, JsonNode value) {
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            target.put(key, value.asText());
        }
    }

    private static void copyNumber(Map<String, Object> target, String key, JsonNode value) {
        if (value != null && value.isNumber()) {
            target.put(key, value.numberValue());
        }
    }

    private static void copyBoolean(Map<String, Object> target, String key, JsonNode value) {
        if (value != null && value.isBoolean()) {
            target.put(key, value.booleanValue());
        }
    }

    private static List<String> textList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            values.add(value.asText());
        }
        return List.copyOf(values);
    }

    private static AgentToolExecutionException controlled(
            WebResearchException exception,
            Map<String, Object> attributes) {
        return new AgentToolExecutionException(
                exception.code(), exception.userMessage(), attributes, exception);
    }

    private static AgentToolExecutionException augment(
            AgentToolExecutionException exception,
            Map<String, Object> attributes) {
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.putAll(exception.diagnosticAttributes());
        return new AgentToolExecutionException(
                exception.errorCode(), exception.userMessage(), merged, exception.getCause());
    }

    private static Map<String, Object> searchExecutionContext(String query, int limit) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("query", query);
        attributes.put("requestedUrl", requestUrl(query));
        attributes.put("provider", PROVIDER);
        attributes.put("providerHost", "html.duckduckgo.com");
        attributes.put("resultLimit", limit);
        attributes.put("discoveryOnly", true);
        attributes.put("evidenceProduced", false);
        attributes.put("safetyBoundary", Map.of(
                "fixedProviderEndpoint", true,
                "responseBodyBounded", true,
                "resultUrlsRequireFetchBeforeUse", true));
        return attributes;
    }

    private static boolean providerMetadataAvailable(WebSearchResponse response) {
        return response.requestUrl() != null
                || response.finalUrl() != null
                || response.statusCode() >= 0
                || response.bodyBytes() >= 0
                || !response.contentType().isBlank()
                || !response.redirectChain().isEmpty();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
