package com.chtholly.agent.web;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Parses the stable DuckDuckGo HTML endpoint into bounded organic search results.
 */
@Slf4j
public final class DuckDuckGoHtmlSearchProvider implements WebSearchProvider {

    private static final String ENDPOINT = "https://html.duckduckgo.com/html/?q=";
    private static final int MAX_RESULTS = 8;

    private final SafeWebHttpClient client;

    /**
     * Creates a DuckDuckGo HTML provider.
     *
     * @param client safe web client
     */
    public DuckDuckGoHtmlSearchProvider(SafeWebHttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Searches the fixed DuckDuckGo HTML endpoint.
     *
     * @param query search query
     * @param limit requested result count, capped at eight
     * @return organic results
     * @throws WebResearchException when the provider challenges the client or its DOM is incompatible
     */
    @Override
    public List<WebSearchResult> search(String query, int limit) {
        return searchDetailed(query, limit).results();
    }

    /**
     * Searches DuckDuckGo and retains bounded provider HTTP diagnostics.
     *
     * @param query search query
     * @param limit requested result count, capped at eight
     * @return immutable detailed provider response
     */
    @Override
    public WebSearchResponse searchDetailed(String query, int limit) {
        if (query == null || query.isBlank()) {
            throw new WebResearchException("WEB_SEARCH_QUERY_INVALID", "The web search query is empty.");
        }
        int boundedLimit = Math.min(MAX_RESULTS, Math.max(1, limit));
        URI uri = URI.create(ENDPOINT + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
        SafeWebResponse response = client.get(uri);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw unavailable();
        }

        Document document = Jsoup.parse(response.bodyAsUtf8(), uri.toString());
        if (document.selectFirst("#challenge-form, .anomaly-modal, form[action*=challenge]") != null) {
            throw unavailable();
        }
        List<Element> anchors = document.select(".result__a");
        if (anchors.isEmpty()) {
            throw unavailable();
        }

        List<WebSearchResult> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element anchor : anchors) {
            Element container = anchor.closest(".result");
            if (container != null && (container.hasClass("result--ad")
                    || !container.select(".badge--ad, .result__badge--ad").isEmpty())) {
                continue;
            }
            String decoded = decodeTarget(anchor.attr("href"), uri);
            if (decoded == null || !seen.add(decoded)) {
                continue;
            }
            String title = anchor.text().trim();
            if (title.isEmpty()) {
                continue;
            }
            Element snippet = container == null ? null : container.selectFirst(".result__snippet");
            results.add(new WebSearchResult(title, decoded,
                    snippet == null ? "" : snippet.text().trim(), results.size() + 1));
            if (results.size() >= boundedLimit) {
                break;
            }
        }
        if (results.isEmpty()) {
            throw unavailable();
        }
        return new WebSearchResponse(
                uri,
                response.finalUrl(),
                response.statusCode(),
                response.contentType(),
                response.bytes().length,
                response.redirectChain(),
                results);
    }

    private static String decodeTarget(String href, URI base) {
        try {
            URI resolved = base.resolve(href);
            String query = resolved.getRawQuery();
            if (query != null && (query.contains("ad_domain=") || query.contains("ad_provider="))) {
                return null;
            }
            if (query != null && isDuckDuckGoHost(resolved.getHost())) {
                for (String pair : query.split("&")) {
                    int separator = pair.indexOf('=');
                    if (separator > 0 && pair.substring(0, separator).equals("uddg")) {
                        resolved = URI.create(URLDecoder.decode(
                                pair.substring(separator + 1), StandardCharsets.UTF_8));
                        break;
                    }
                }
            }
            String scheme = resolved.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            String host = resolved.getHost();
            if (host == null || host.toLowerCase(Locale.ROOT).contains("ad.doubleclick.net")) {
                return null;
            }
            if (resolved.getRawUserInfo() != null) {
                return null;
            }
            return canonicalTarget(resolved.normalize());
        } catch (Exception exception) {
            log.debug("Discarding invalid web search result URL", exception);
            return null;
        }
    }

    private static boolean isDuckDuckGoHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("duckduckgo.com") || normalized.endsWith(".duckduckgo.com");
    }

    private static String canonicalTarget(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        host = host.toLowerCase(Locale.ROOT);
        boolean ipv6 = host.contains(":");
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80)
                || (scheme.equals("https") && port == 443);
        StringBuilder target = new StringBuilder(scheme).append("://");
        target.append(ipv6 ? "[" + host + "]" : host);
        if (!defaultPort) {
            target.append(':').append(port);
        }
        if (uri.getRawPath() != null) {
            target.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null) {
            target.append('?').append(uri.getRawQuery());
        }
        return target.toString();
    }

    private static WebResearchException unavailable() {
        return new WebResearchException(
                "WEB_PROVIDER_UNAVAILABLE", "The web search provider is temporarily unavailable.");
    }
}
