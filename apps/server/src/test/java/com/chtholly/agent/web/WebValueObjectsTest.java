package com.chtholly.agent.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class WebValueObjectsTest {

    @Test
    void safeResponseDefensivelyCopiesMutableContent() {
        URI original = URI.create("https://example.com/original");
        List<URI> redirects = new ArrayList<>(List.of(original));
        byte[] bytes = new byte[]{'o', 'k'};
        SafeWebResponse response = new SafeWebResponse(original, 200, redirects, "text/plain", bytes);

        redirects.clear();
        bytes[0] = 'x';
        byte[] exposed = response.bytes();
        exposed[1] = 'x';

        assertThat(response.redirectChain()).containsExactly(original);
        assertThat(response.bodyAsUtf8()).isEqualTo("ok");
        assertThat(response.bytes()).containsExactly((byte) 'o', (byte) 'k');
    }

    @Test
    void transportRequestCopiesHeadersAndRejectsNulls() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "text/plain");
        WebTransportRequest request = new WebTransportRequest(
                URI.create("https://example.com"), Duration.ofSeconds(1), headers);

        headers.put("Authorization", "secret");

        assertThat(request.headers()).containsOnlyKeys("Accept");
        assertThatNullPointerException().isThrownBy(() ->
                new WebTransportRequest(null, Duration.ofSeconds(1), Map.of()));
        assertThatNullPointerException().isThrownBy(() ->
                new WebTransportRequest(URI.create("https://example.com"), null, Map.of()));
        assertThatNullPointerException().isThrownBy(() ->
                new WebTransportRequest(URI.create("https://example.com"), Duration.ofSeconds(1), null));
    }

    @Test
    void transportRequestDefensivelyCopiesPinnedAddresses() throws Exception {
        List<InetAddress> addresses = new ArrayList<>(List.of(InetAddress.getByName("93.184.216.34")));
        WebTransportRequest request = pinnedRequest(addresses);

        addresses.clear();

        assertThat(pinnedAddresses(request)).extracting(InetAddress::getHostAddress)
                .containsExactly("93.184.216.34");
    }

    @Test
    void transportResponseDeepCopiesHeadersAndRejectsNulls() throws Exception {
        List<String> values = new ArrayList<>(List.of("text/plain"));
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("content-type", values);
        try (WebTransportResponse response = new WebTransportResponse(
                200, headers, new ByteArrayInputStream(new byte[0]))) {
            values.add("application/json");
            headers.clear();

            assertThat(response.headers()).containsOnlyKeys("content-type");
            assertThat(response.headers().get("content-type")).containsExactly("text/plain");
        }
        assertThatNullPointerException().isThrownBy(() ->
                new WebTransportResponse(200, null, new ByteArrayInputStream(new byte[0])));
        assertThatNullPointerException().isThrownBy(() ->
                new WebTransportResponse(200, Map.of(), null));
    }

    @Test
    void safeResponseRejectsNulls() {
        URI uri = URI.create("https://example.com");
        assertThatNullPointerException().isThrownBy(() ->
                new SafeWebResponse(null, 200, List.of(uri), "text/plain", new byte[0]));
        assertThatNullPointerException().isThrownBy(() ->
                new SafeWebResponse(uri, 200, null, "text/plain", new byte[0]));
        assertThatNullPointerException().isThrownBy(() ->
                new SafeWebResponse(uri, 200, List.of(uri), null, new byte[0]));
        assertThatNullPointerException().isThrownBy(() ->
                new SafeWebResponse(uri, 200, List.of(uri), "text/plain", null));
    }

    @Test
    void detailedSearchResponseDefensivelyCopiesResultsAndRedirects() {
        URI requestUrl = URI.create("https://html.duckduckgo.com/html/?q=query");
        List<URI> redirects = new ArrayList<>(List.of(requestUrl));
        List<WebSearchResult> results = new ArrayList<>(List.of(
                new WebSearchResult("Title", "https://example.com", "Snippet", 1)));
        WebSearchResponse response = new WebSearchResponse(
                requestUrl, requestUrl, 200, "text/html", 42, redirects, results);

        redirects.clear();
        results.clear();

        assertThat(response.redirectChain()).containsExactly(requestUrl);
        assertThat(response.results()).hasSize(1);
    }

    @Test
    void legacySearchProvidersReceiveResultsOnlyDetailedCompatibility() {
        WebSearchProvider provider = (query, limit) -> List.of(
                new WebSearchResult("Title", "https://example.com", "Snippet", 1));

        WebSearchResponse response = provider.searchDetailed("query", 5);

        assertThat(response.requestUrl()).isNull();
        assertThat(response.finalUrl()).isNull();
        assertThat(response.statusCode()).isEqualTo(-1);
        assertThat(response.bodyBytes()).isEqualTo(-1);
        assertThat(response.redirectChain()).isEmpty();
        assertThat(response.results()).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    private static WebTransportRequest pinnedRequest(List<InetAddress> addresses) throws Exception {
        Constructor<WebTransportRequest> constructor;
        try {
            constructor = (Constructor<WebTransportRequest>) WebTransportRequest.class.getConstructor(
                    URI.class, Duration.class, Map.class, List.class);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("WebTransportRequest must carry validated addresses", exception);
        }
        return constructor.newInstance(
                URI.create("https://example.com"), Duration.ofSeconds(1), Map.of(), addresses);
    }

    @SuppressWarnings("unchecked")
    private static List<InetAddress> pinnedAddresses(WebTransportRequest request) throws Exception {
        return (List<InetAddress>) request.getClass().getMethod("resolvedAddresses").invoke(request);
    }
}
