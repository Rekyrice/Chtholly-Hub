package com.chtholly.agent.tools;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.evidence.Evidence;
import com.chtholly.agent.observability.AgentToolDiagnostics;
import com.chtholly.agent.runtime.AgentToolExecutionException;
import com.chtholly.agent.runtime.AgentToolOutput;
import com.chtholly.agent.web.ExtractedWebPage;
import com.chtholly.agent.web.RobotsPolicyResult;
import com.chtholly.agent.web.RobotsPolicyService;
import com.chtholly.agent.web.SafeWebHttpClient;
import com.chtholly.agent.web.SafeWebResponse;
import com.chtholly.agent.web.WebPageExtractor;
import com.chtholly.agent.web.WebResearchException;
import com.chtholly.agent.web.WebUrlPolicy;
import com.chtholly.common.ratelimit.RateLimiter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Fetches one public page through the SSRF-safe HTTP and robots boundary. */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class WebFetchTool implements AgentTool {

    private static final int DEFAULT_MAX_CHARS = 8_000;
    private static final int MIN_CHARS = 1_000;
    private static final int MAX_CHARS = 12_000;

    private final SafeWebHttpClient client;
    private final RobotsPolicyService robotsPolicyService;
    private final WebPageExtractor extractor;
    private final WebUrlPolicy urlPolicy;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    /**
     * Creates a public page fetch tool.
     *
     * @param client SSRF-safe bounded HTTP client
     * @param robotsPolicyService fail-closed robots evaluator
     * @param extractor bounded readable-page extractor
     * @param urlPolicy URL and DNS safety policy
     * @param rateLimiter shared Redis-backed rate limiter
     * @param objectMapper JSON codec used for immutable observation envelopes
     */
    public WebFetchTool(
            SafeWebHttpClient client,
            RobotsPolicyService robotsPolicyService,
            WebPageExtractor extractor,
            WebUrlPolicy urlPolicy,
            RateLimiter rateLimiter,
            ObjectMapper objectMapper) {
        this.client = Objects.requireNonNull(client, "client");
        this.robotsPolicyService = Objects.requireNonNull(robotsPolicyService, "robotsPolicyService");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.urlPolicy = Objects.requireNonNull(urlPolicy, "urlPolicy");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "读取一个公开网页，遵守 robots.txt，并把抽取正文登记为本轮可引用 Evidence。";
    }

    @Override
    public Map<String, ParamDef> parameterSchema() {
        return Map.of(
                "url", ParamDef.string("要读取的公开 HTTP 或 HTTPS 网页地址", true, 1, 2_048),
                "maxChars", ParamDef.integer("返回给模型的最大正文字符数", false, MIN_CHARS, MAX_CHARS));
    }

    @Override
    public String execute(Map<String, Object> input, long userId) {
        return executeDetailed(input, userId).observation();
    }

    @Override
    public AgentToolOutput executeDetailed(Map<String, Object> input, long userId) {
        String rawUrl = requiredUrl(input);
        int maxChars = boundedInteger(input.get("maxChars"), DEFAULT_MAX_CHARS, MIN_CHARS, MAX_CHARS);
        try {
            URI requested = validateRequestedUrl(rawUrl);
            Map<String, Object> executionContext = fetchExecutionContext(requested, maxChars);
            try {
                WebResearchRateLimits.acquireFetchUser(rateLimiter, userId);
            } catch (AgentToolExecutionException exception) {
                throw augment(exception, merge(executionContext, Map.of("failureStage", "user_rate_limit")));
            }
            Set<String> rateLimitedHosts = new LinkedHashSet<>();
            List<Map<String, Object>> robotsChecks = new ArrayList<>();
            Consumer<URI> perTargetGuard = target -> guardTarget(
                    target, executionContext, rateLimitedHosts, robotsChecks);

            SafeWebResponse response;
            try {
                response = client.get(requested, perTargetGuard);
            } catch (AgentToolExecutionException exception) {
                throw exception;
            } catch (WebResearchException exception) {
                throw controlled(exception, merge(executionContext, Map.of(
                        "failureStage", "safe_http",
                        "robotsChecks", List.copyOf(robotsChecks))));
            }
            Map<String, Object> robotsContext = merge(executionContext, Map.of(
                    "robots", firstRobotsCheck(robotsChecks),
                    "robotsChecks", List.copyOf(robotsChecks)));
            Map<String, Object> responseContext = merge(
                    robotsContext, responseAttributes(response));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AgentToolExecutionException(
                        "WEB_FETCH_HTTP_ERROR",
                        "目标网页返回了 HTTP " + response.statusCode() + "，暂时无法读取。",
                        responseContext);
            }
            ExtractedWebPage extracted;
            try {
                extracted = extractor.extract(response);
            } catch (WebResearchException exception) {
                throw controlled(exception, responseContext);
            }
            if (extracted == null || extracted.text() == null || extracted.text().isBlank()) {
                throw new AgentToolExecutionException(
                        "WEB_EXTRACTION_EMPTY",
                        "目标网页没有可读取的正文内容。",
                        responseContext);
            }

            URI authoritativeUrl = response.finalUrl();
            DeclaredCanonical declaredCanonical = inspectDeclaredCanonical(
                    extracted.declaredCanonicalUrl(), authoritativeUrl);
            String title = normalizedTitle(extracted.title(), authoritativeUrl);
            String returnedText = bounded(extracted.text(), maxChars);
            Evidence evidence = Evidence.fromWebPage(
                    authoritativeUrl.toString(),
                    title,
                    extracted.contentSha256(),
                    returnedText);
            String observation;
            try {
                observation = writeObservation(buildEnvelope(
                        requested,
                        response,
                        robotsChecks,
                        extracted,
                        authoritativeUrl,
                        declaredCanonical,
                        title,
                        returnedText,
                        maxChars,
                        evidence));
            } catch (AgentToolExecutionException exception) {
                throw augment(exception, merge(responseContext, extractionAttributes(extracted, returnedText)));
            }
            return new AgentToolOutput(observation, List.of(evidence));
        } catch (AgentToolExecutionException exception) {
            throw exception;
        } catch (WebResearchException exception) {
            throw controlled(exception, rawFetchExecutionContext(rawUrl, maxChars));
        }
    }

    @Override
    public AgentToolDiagnostics traceDiagnostics(Map<String, Object> input, String observation) {
        AgentToolDiagnostics base = AgentTool.super.traceDiagnostics(input, observation)
                .withProvider(providerFrom(inputText(input, "url")))
                .withSourcePolicy("robots_checked_public_http");
        Map<String, Object> attributes = new LinkedHashMap<>();
        String requestedUrl = inputText(input, "url").strip();
        if (!requestedUrl.isBlank()) {
            attributes.put("requestedUrl", diagnosticUrl(requestedUrl));
        }
        attributes.put("maxChars", boundedInteger(input == null ? null : input.get("maxChars"),
                DEFAULT_MAX_CHARS, MIN_CHARS, MAX_CHARS));
        attributes.put("safetyBoundary", safetyBoundary());

        JsonNode root = readObservation(observation);
        Integer resultCount = null;
        List<String> selectedIds = List.of();
        if (root != null && "web_fetched_page".equals(root.path("kind").asText())) {
            resultCount = 1;
            copyText(attributes, "requestedUrl", root.path("requestedUrl"));
            copyText(attributes, "finalUrl", root.path("finalUrl"));
            copyText(attributes, "canonicalUrl", root.path("canonicalUrl"));
            attributes.put("redirectChain", textList(root.path("redirectChain")));
            attributes.put("robots", jsonObject(root.path("robots")));
            attributes.put("robotsChecks", jsonValue(root.path("robotsChecks")));
            JsonNode http = root.path("http");
            copyNumber(attributes, "httpStatus", http.path("status"));
            copyText(attributes, "contentType", http.path("contentType"));
            copyNumber(attributes, "responseBytes", http.path("bytes"));
            copyText(attributes, "declaredCharset", http.path("declaredCharset"));
            copyText(attributes, "charset", http.path("charset"));
            copyText(attributes, "effectiveCharset", http.path("effectiveCharset"));
            JsonNode extraction = root.path("extraction");
            copyText(attributes, "extractor", extraction.path("strategy"));
            copyNumber(attributes, "rawChars", extraction.path("rawChars"));
            copyNumber(attributes, "extractedChars", extraction.path("extractedChars"));
            copyNumber(attributes, "returnedChars", extraction.path("returnedChars"));
            copyBoolean(attributes, "truncatedByMaxChars", extraction.path("truncatedByMaxChars"));
            copyText(attributes, "contentSha256", extraction.path("contentSha256"));
            attributes.put("canonicalResolution", jsonObject(root.path("canonicalResolution")));
            attributes.put("evidence", jsonObject(root.path("evidence")));
            String canonicalUrl = root.path("canonicalUrl").asText("");
            if (!canonicalUrl.isBlank()) {
                base = base.withProvider(providerFrom(canonicalUrl));
            }
            String evidenceId = root.path("evidence").path("evidenceId").asText("");
            if (!evidenceId.isBlank()) {
                selectedIds = List.of(evidenceId);
            }
        }
        return base.withResultCount(resultCount)
                .withSelectedIds(selectedIds)
                .withAttributes(attributes);
    }

    private URI validateRequestedUrl(String rawUrl) {
        try {
            URI validated = urlPolicy.validate(URI.create(rawUrl));
            if (validated == null || validated.getHost() == null || validated.getHost().isBlank()) {
                throw new AgentToolExecutionException(
                        "WEB_URL_INVALID",
                        "网页地址格式无效。");
            }
            return validated;
        } catch (IllegalArgumentException exception) {
            throw new AgentToolExecutionException(
                    "WEB_URL_INVALID",
                    "网页地址格式无效。",
                    Map.of(),
                    exception);
        }
    }

    private void guardTarget(
            URI target,
            Map<String, Object> executionContext,
            Set<String> rateLimitedHosts,
            List<Map<String, Object>> robotsChecks) {
        String host = target.getHost().toLowerCase(Locale.ROOT);
        Map<String, Object> hopContext = merge(executionContext, Map.of(
                "currentHopUrl", target.toString(),
                "currentHopHost", host,
                "robotsChecks", List.copyOf(robotsChecks)));
        if (rateLimitedHosts.add(host)) {
            try {
                WebResearchRateLimits.acquireFetchHost(rateLimiter, host);
            } catch (AgentToolExecutionException exception) {
                throw augment(exception, merge(hopContext, Map.of(
                        "failureStage", "hop_host_rate_limit")));
            }
        }

        RobotsPolicyResult result;
        try {
            result = robotsPolicyService.check(target);
        } catch (WebResearchException exception) {
            Map<String, Object> failedCheck = failedRobotsCheck(target, exception.code());
            robotsChecks.add(failedCheck);
            throw controlled(exception, merge(hopContext, Map.of(
                    "failureStage", "hop_robots_check",
                    "robots", failedCheck,
                    "robotsChecks", List.copyOf(robotsChecks))));
        } catch (RuntimeException exception) {
            Map<String, Object> failedCheck = failedRobotsCheck(target, "WEB_ROBOTS_FETCH_FAILED");
            robotsChecks.add(failedCheck);
            throw new AgentToolExecutionException(
                    "WEB_ROBOTS_FETCH_FAILED",
                    "目标网站的 robots.txt 暂时无法检查。",
                    merge(hopContext, Map.of(
                            "failureStage", "hop_robots_check",
                            "robots", failedCheck,
                            "robotsChecks", List.copyOf(robotsChecks))),
                    exception);
        }

        Map<String, Object> check = robotsCheckAttributes(target, result);
        robotsChecks.add(check);
        if (!result.allowed()) {
            String code = result.errorCode() == null || result.errorCode().isBlank()
                    ? "WEB_ROBOTS_DENIED" : result.errorCode();
            throw new AgentToolExecutionException(
                    code,
                    "目标网站的 robots.txt 不允许读取这个页面。",
                    merge(hopContext, Map.of(
                            "failureStage", "hop_robots_denied",
                            "robots", check,
                            "robotsChecks", List.copyOf(robotsChecks))));
        }
    }

    private DeclaredCanonical inspectDeclaredCanonical(URI declared, URI authoritativeUrl) {
        if (declared == null) {
            return new DeclaredCanonical(null, "absent");
        }
        if (declared.equals(authoritativeUrl)) {
            return new DeclaredCanonical(declared.toString(), "identical");
        }
        return new DeclaredCanonical(
                declared.toString(),
                sameOrigin(declared, authoritativeUrl) ? "same_origin" : "cross_origin");
    }

    private Map<String, Object> buildEnvelope(
            URI requested,
            SafeWebResponse response,
            List<Map<String, Object>> robotsChecks,
            ExtractedWebPage extracted,
            URI authoritativeUrl,
            DeclaredCanonical declaredCanonical,
            String title,
            String returnedText,
            int maxChars,
            Evidence evidence) {
        Map<String, Object> http = new LinkedHashMap<>();
        http.put("status", response.statusCode());
        http.put("contentType", response.contentType());
        http.put("bytes", response.bytes() == null ? 0 : response.bytes().length);
        http.put("declaredCharset", response.charset().isBlank() ? null : response.charset());
        http.put("charset", extracted.charset());
        http.put("effectiveCharset", extracted.charset());

        Map<String, Object> extraction = new LinkedHashMap<>();
        extraction.put("strategy", extracted.extractor());
        extraction.put("rawChars", extracted.rawChars());
        extraction.put("extractedChars", extracted.extractedChars());
        extraction.put("returnedChars", returnedText.length());
        extraction.put("maxChars", maxChars);
        extraction.put("truncatedByMaxChars", returnedText.length() < extracted.text().length());
        extraction.put("contentSha256", extracted.contentSha256());
        extraction.put("effectiveCharset", extracted.charset());

        Map<String, Object> canonicalDetails = new LinkedHashMap<>();
        canonicalDetails.put("authoritySource", "response_final_url");
        canonicalDetails.put("authoritativeUrl", authoritativeUrl.toString());
        canonicalDetails.put("declaredCanonicalUrl", declaredCanonical.url());
        canonicalDetails.put("declaredRelationship", declaredCanonical.relationship());
        canonicalDetails.put("declaredCanonicalAdopted", false);

        Map<String, Object> page = new LinkedHashMap<>();
        page.put("title", title);
        page.put("text", returnedText);

        Map<String, Object> evidenceDetails = new LinkedHashMap<>();
        evidenceDetails.put("evidenceId", evidence.evidenceId());
        evidenceDetails.put("citationAssignment", "agent_loop");
        evidenceDetails.put("citationId", null);
        evidenceDetails.put("sourceType", evidence.sourceType());
        evidenceDetails.put("documentId", evidence.documentId());
        evidenceDetails.put("sourceVersion", evidence.sourceVersion());
        evidenceDetails.put("sourceHash", evidence.sourceHash());
        evidenceDetails.put("trust", evidence.trust());
        evidenceDetails.put("permissions", evidence.permissions());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("kind", "web_fetched_page");
        envelope.put("requestedUrl", requested.toString());
        envelope.put("finalUrl", response.finalUrl().toString());
        envelope.put("canonicalUrl", authoritativeUrl.toString());
        envelope.put("declaredCanonicalUrl", declaredCanonical.url());
        envelope.put("redirectChain", response.redirectChain().stream().map(URI::toString).toList());
        envelope.put("robots", firstRobotsCheck(robotsChecks));
        envelope.put("robotsChecks", List.copyOf(robotsChecks));
        envelope.put("http", http);
        envelope.put("extraction", extraction);
        envelope.put("canonicalResolution", canonicalDetails);
        envelope.put("safetyBoundary", safetyBoundary());
        envelope.put("page", page);
        envelope.put("evidence", evidenceDetails);
        return envelope;
    }

    private static Map<String, Object> safetyBoundary() {
        Map<String, Object> boundary = new LinkedHashMap<>();
        boundary.put("schemes", List.of("http", "https"));
        boundary.put("ports", List.of(80, 443));
        boundary.put("dnsValidation", "all_public_dns_answers");
        boundary.put("redirectPolicy", "redirect_revalidation_no_https_downgrade");
        boundary.put("redirectLimit", SafeWebHttpClient.MAX_REDIRECTS);
        boundary.put("bodyLimitBytes", SafeWebHttpClient.MAX_BODY_BYTES);
        boundary.put("contentTypes", List.of("text/html", "application/xhtml+xml", "text/plain"));
        boundary.put("credentialsSent", false);
        return boundary;
    }

    private String writeObservation(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new AgentToolExecutionException(
                    "WEB_OBSERVATION_ENCODING_FAILED",
                    "网页内容暂时无法整理，请稍后重试。",
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

    private static Map<String, Object> jsonObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, Object> output = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry ->
                output.put(entry.getKey(), jsonValue(entry.getValue())));
        return output;
    }

    private static Object jsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return jsonObject(node);
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonNode value : node) {
                values.add(jsonValue(value));
            }
            return List.copyOf(values);
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return node.asText();
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

    private static String requiredUrl(Map<String, Object> input) {
        String url = inputText(input, "url").strip();
        if (url.isBlank() || url.length() > 2_048) {
            throw new AgentToolExecutionException(
                    "WEB_URL_INVALID",
                    "网页地址不能为空，且长度不能超过 2048 个字符。");
        }
        return url;
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

    private static String normalizedTitle(String title, URI canonical) {
        if (title != null && !title.isBlank()) {
            return bounded(title.strip(), 500);
        }
        String host = canonical.getHost();
        return host == null || host.isBlank() ? canonical.toString() : host;
    }

    private static String providerFrom(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null || host.isBlank() ? "public_web" : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            return "public_web";
        }
    }

    private static String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static String diagnosticUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getRawUserInfo() == null) {
                return value;
            }
            return new URI(
                    uri.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment()).toASCIIString();
        } catch (Exception exception) {
            return "[INVALID_URL]";
        }
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

    private static Map<String, Object> rawFetchExecutionContext(String requestedUrl, int maxChars) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("requestedUrl", diagnosticUrl(requestedUrl));
        attributes.put("providerHost", providerFrom(requestedUrl));
        attributes.put("maxChars", maxChars);
        attributes.put("safetyBoundary", safetyBoundary());
        return attributes;
    }

    private static Map<String, Object> fetchExecutionContext(URI requested, int maxChars) {
        Map<String, Object> attributes = new LinkedHashMap<>(
                rawFetchExecutionContext(requested.toString(), maxChars));
        attributes.put("providerHost", requested.getHost().toLowerCase(Locale.ROOT));
        return attributes;
    }

    private static Map<String, Object> responseAttributes(SafeWebResponse response) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("finalUrl", response.finalUrl().toString());
        attributes.put("redirectChain", response.redirectChain().stream().map(URI::toString).toList());
        attributes.put("httpStatus", response.statusCode());
        attributes.put("contentType", response.contentType());
        attributes.put("responseBytes", response.bytes() == null ? 0 : response.bytes().length);
        if (!response.charset().isBlank()) {
            attributes.put("declaredCharset", response.charset());
            attributes.put("charset", response.charset());
        }
        return attributes;
    }

    private static Map<String, Object> extractionAttributes(
            ExtractedWebPage extracted,
            String returnedText) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("extractor", extracted.extractor());
        attributes.put("rawChars", extracted.rawChars());
        attributes.put("extractedChars", extracted.extractedChars());
        attributes.put("returnedChars", returnedText.length());
        attributes.put("contentSha256", extracted.contentSha256());
        attributes.put("charset", extracted.charset());
        attributes.put("effectiveCharset", extracted.charset());
        return attributes;
    }

    private static Map<String, Object> robotsCheckAttributes(
            URI target,
            RobotsPolicyResult robots) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("url", target.toString());
        attributes.put("host", target.getHost().toLowerCase(Locale.ROOT));
        attributes.put("decision", robots.decision().name());
        attributes.put("allowed", robots.allowed());
        attributes.put("cacheHit", robots.cacheHit());
        attributes.put("matchedRule", robots.matchedRule());
        attributes.put("errorCode", robots.errorCode());
        return attributes;
    }

    private static Map<String, Object> failedRobotsCheck(URI target, String errorCode) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("url", target.toString());
        attributes.put("host", target.getHost().toLowerCase(Locale.ROOT));
        attributes.put("decision", "ERROR");
        attributes.put("allowed", false);
        attributes.put("cacheHit", false);
        attributes.put("matchedRule", null);
        attributes.put("errorCode", errorCode);
        return attributes;
    }

    private static Map<String, Object> firstRobotsCheck(List<Map<String, Object>> checks) {
        return checks == null || checks.isEmpty() ? Map.of() : checks.getFirst();
    }

    private static Map<String, Object> merge(
            Map<String, Object> left,
            Map<String, Object> right) {
        Map<String, Object> merged = new LinkedHashMap<>(left);
        merged.putAll(right);
        return merged;
    }

    private static boolean sameOrigin(URI left, URI right) {
        return normalizedScheme(left).equals(normalizedScheme(right))
                && normalizedHost(left).equals(normalizedHost(right))
                && effectivePort(left) == effectivePort(right);
    }

    private static String normalizedScheme(URI uri) {
        return uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    }

    private static String normalizedHost(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equals(normalizedScheme(uri)) ? 443 : 80;
    }

    private record DeclaredCanonical(String url, String relationship) {
    }
}
