package com.chtholly.agent.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DuckDuckGoHtmlSearchProviderTest {

    @Test
    void parsesOrganicResultsDecodesRedirectsAndDeduplicates() throws Exception {
        String html = """
                <html><body>
                  <div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa">Alpha</a><a class="result__snippet">Alpha snippet</a></div>
                  <div class="result"><a class="result__a" href="https://example.com/a">Duplicate</a><a class="result__snippet">Dup</a></div>
                  <div class="result result--ad"><a class="result__a" href="https://ads.example/a">Ad</a></div>
                  <div class="result"><a class="result__a" href="//duckduckgo.com/y.js?ad_domain=ads.example">Hidden ad</a></div>
                  <div class="result"><a class="result__a" href="https://EXAMPLE.com:443/a#section">Normalized duplicate</a></div>
                  <div class="result"><a class="result__a" href="https://example.org/%E4%B8%AD?q=a%20b">Beta</a><div class="result__snippet">Beta snippet</div></div>
                </body></html>
                """;
        SingleTransport transport = new SingleTransport(html);
        DuckDuckGoHtmlSearchProvider provider = provider(transport);

        List<WebSearchResult> results = provider.search("safe web research", 8);

        assertThat(transport.request.uri().toString())
                .isEqualTo("https://html.duckduckgo.com/html/?q=safe+web+research");
        assertThat(results).extracting(WebSearchResult::url)
                .containsExactly("https://example.com/a", "https://example.org/%E4%B8%AD?q=a%20b");
        assertThat(results).extracting(WebSearchResult::rank).containsExactly(1, 2);
        assertThat(results.getFirst().snippet()).isEqualTo("Alpha snippet");
    }

    @Test
    void reportsProviderUnavailableForCaptchaOrDomMismatch() throws Exception {
        for (String body : List.of(
                "<html><body><form id=\"challenge-form\"></form></body></html>",
                "<html><body>changed</body></html>")) {
            DuckDuckGoHtmlSearchProvider provider = provider(new SingleTransport(body));
            assertThatThrownBy(() -> provider.search("query", 5))
                    .isInstanceOf(WebResearchException.class)
                    .extracting(error -> ((WebResearchException) error).code())
                    .isEqualTo("WEB_PROVIDER_UNAVAILABLE");
        }
    }

    @Test
    void doesNotTreatResultTextContainingCaptchaAsAChallenge() throws Exception {
        String html = """
                <html><body><div class="result">
                <a class="result__a" href="https://example.com/captcha">Captcha guidance</a>
                <div class="result__snippet">How to implement a captcha challenge.</div>
                </div></body></html>
                """;

        List<WebSearchResult> results = provider(new SingleTransport(html)).search("captcha", 5);

        assertThat(results).singleElement().satisfies(result ->
                assertThat(result.url()).isEqualTo("https://example.com/captcha"));
    }

    @Test
    void onlyDecodesRedirectsFromDuckDuckGoHostBoundary() throws Exception {
        String html = """
                <html><body><div class="result">
                <a class="result__a" href="https://notduckduckgo.com/?uddg=https%3A%2F%2Fexample.com%2Fspoofed">Result</a>
                </div></body></html>
                """;

        List<WebSearchResult> results = provider(new SingleTransport(html)).search("query", 5);

        assertThat(results).extracting(WebSearchResult::url)
                .containsExactly("https://notduckduckgo.com/?uddg=https%3A%2F%2Fexample.com%2Fspoofed");
    }

    @Test
    void detailedSearchReportsImmutableHttpMetadata() throws Exception {
        String html = """
                <html><body><div class="result">
                <a class="result__a" href="https://example.com/result">Result</a>
                </div></body></html>
                """;
        DuckDuckGoHtmlSearchProvider provider = provider(new SingleTransport(html));

        Object detailed = detailedSearch(provider, "query", 5);

        assertThat(property(detailed, "requestUrl"))
                .isEqualTo(URI.create("https://html.duckduckgo.com/html/?q=query"));
        assertThat(property(detailed, "finalUrl"))
                .isEqualTo(URI.create("https://html.duckduckgo.com/html/?q=query"));
        assertThat(property(detailed, "statusCode")).isEqualTo(200);
        assertThat(property(detailed, "contentType")).isEqualTo("text/html");
        assertThat(property(detailed, "bodyBytes"))
                .isEqualTo(html.getBytes(StandardCharsets.UTF_8).length);
        assertThat(property(detailed, "redirectChain"))
                .isEqualTo(List.of(URI.create("https://html.duckduckgo.com/html/?q=query")));
        assertThat((List<?>) property(detailed, "results")).hasSize(1);
    }

    private static DuckDuckGoHtmlSearchProvider provider(SingleTransport transport) throws Exception {
        WebUrlPolicy policy = new WebUrlPolicy(host -> List.of(InetAddress.getByName("52.142.124.215")));
        return new DuckDuckGoHtmlSearchProvider(new SafeWebHttpClient(policy, transport));
    }

    private static Object detailedSearch(WebSearchProvider provider, String query, int limit) throws Exception {
        try {
            return provider.getClass().getMethod("searchDetailed", String.class, int.class)
                    .invoke(provider, query, limit);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("WebSearchProvider must expose detailed search metadata", exception);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private static Object property(Object value, String name) throws Exception {
        return value.getClass().getMethod(name).invoke(value);
    }

    private static final class SingleTransport implements WebHttpTransport {
        private final String body;
        private WebTransportRequest request;

        private SingleTransport(String body) {
            this.body = body;
        }

        @Override
        public WebTransportResponse execute(WebTransportRequest request) {
            this.request = request;
            return new WebTransportResponse(200, Map.of("content-type", List.of("text/html")),
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
