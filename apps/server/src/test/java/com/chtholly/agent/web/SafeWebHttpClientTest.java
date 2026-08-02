package com.chtholly.agent.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeWebHttpClientTest {

    @Test
    void followsValidatedRedirectsAndUsesSafeHeaders() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(302, Map.of("location", List.of("/next")), "");
        transport.add(200, Map.of("content-type", List.of("text/html; charset=UTF-8")), "<p>ok</p>");

        SafeWebResponse response = client(transport).get(URI.create("https://example.com/start"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.finalUrl()).isEqualTo(URI.create("https://example.com/next"));
        assertThat(response.redirectChain()).containsExactly(
                URI.create("https://example.com/start"), URI.create("https://example.com/next"));
        assertThat(response.contentType()).isEqualTo("text/html");
        assertThat(response.bodyAsUtf8()).isEqualTo("<p>ok</p>");
        assertThat(transport.requests).allSatisfy(request -> {
            assertThat(request.timeout()).isEqualTo(java.time.Duration.ofSeconds(8));
            assertThat(request.headers()).containsEntry("Accept-Encoding", "identity");
            assertThat(request.headers()).containsEntry("User-Agent", "ChthollyHubBot/1.0");
            assertThat(request.headers()).doesNotContainKeys("Cookie", "Authorization");
            assertThat(pinnedAddresses(request)).extracting(InetAddress::getHostAddress)
                    .containsExactly("93.184.216.34");
        });
    }

    @Test
    void preservesDeclaredCharsetForDownstreamDecoding() {
        FakeTransport transport = new FakeTransport();
        String html = "<article>\u4E2D\u6587\u6B63\u6587</article>";
        transport.addBytes(
                200,
                Map.of("content-type", List.of("text/html; charset=GBK")),
                html.getBytes(Charset.forName("GBK")));

        SafeWebResponse response = client(transport).get(URI.create("https://example.com/article"));

        assertThat(declaredCharset(response)).isEqualTo("GBK");
    }

    @Test
    void rejectsUnsupportedDeclaredCharset() {
        FakeTransport transport = new FakeTransport();
        transport.add(
                200,
                Map.of("content-type", List.of("text/html; charset=x-not-a-real-charset")),
                "<article>text</article>");

        assertCode(
                () -> client(transport).get(URI.create("https://example.com/article")),
                "WEB_CHARSET_UNSUPPORTED");
    }

    @Test
    void rejectsHttpsDowngradeAndTooManyRedirects() {
        FakeTransport downgrade = new FakeTransport();
        downgrade.add(302, Map.of("location", List.of("http://example.com/next")), "");
        assertCode(() -> client(downgrade).get(URI.create("https://example.com")), "WEB_REDIRECT_DOWNGRADE");

        FakeTransport loop = new FakeTransport();
        for (int index = 0; index < 4; index++) {
            loop.add(302, Map.of("location", List.of("/" + index)), "");
        }
        assertCode(() -> client(loop).get(URI.create("https://example.com")), "WEB_TOO_MANY_REDIRECTS");
    }

    @Test
    void rejectsUnsupportedContentAndOversizedBodies() {
        FakeTransport unsupported = new FakeTransport();
        unsupported.add(200, Map.of("content-type", List.of("application/json")), "{}");
        assertCode(() -> client(unsupported).get(URI.create("https://example.com")), "WEB_CONTENT_TYPE_UNSUPPORTED");

        FakeTransport length = new FakeTransport();
        length.add(200, Map.of(
                "content-type", List.of("text/plain"),
                "content-length", List.of("1048577")), "ignored");
        assertCode(() -> client(length).get(URI.create("https://example.com")), "WEB_BODY_TOO_LARGE");

        FakeTransport streamed = new FakeTransport();
        streamed.add(200, Map.of("content-type", List.of("text/plain")), "a".repeat(1_048_577));
        assertCode(() -> client(streamed).get(URI.create("https://example.com")), "WEB_BODY_TOO_LARGE");

        FakeTransport compressed = new FakeTransport();
        compressed.add(200, Map.of(
                "content-type", List.of("text/html"),
                "content-encoding", List.of("gzip")), "compressed");
        assertCode(() -> client(compressed).get(URI.create("https://example.com")),
                "WEB_CONTENT_ENCODING_UNSUPPORTED");
    }

    @Test
    void invokesPerTargetGuardBeforeEveryRedirectHop() {
        FakeTransport transport = new FakeTransport();
        transport.add(302, Map.of("location", List.of("https://other.example/next")), "");
        transport.add(200, Map.of("content-type", List.of("text/plain")), "ok");
        List<URI> guarded = new ArrayList<>();

        SafeWebResponse response = getWithGuard(
                client(transport), URI.create("https://example.com/start"), guarded::add);

        assertThat(response.bodyAsUtf8()).isEqualTo("ok");
        assertThat(guarded).containsExactly(
                URI.create("https://example.com/start"), URI.create("https://other.example/next"));
    }

    @Test
    void guardCanRejectRedirectBeforeItIsSent() {
        FakeTransport transport = new FakeTransport();
        transport.add(302, Map.of("location", List.of("https://blocked.example/private")), "");
        Consumer<URI> guard = target -> {
            if (target.getHost().equals("blocked.example")) {
                throw new WebResearchException("WEB_ROBOTS_DENIED", "Page access is disallowed.");
            }
        };

        assertCode(() -> getWithGuard(
                client(transport), URI.create("https://example.com/start"), guard), "WEB_ROBOTS_DENIED");
        assertThat(transport.requests).extracting(WebTransportRequest::uri)
                .containsExactly(URI.create("https://example.com/start"));
    }

    private static SafeWebHttpClient client(FakeTransport transport) {
        WebUrlPolicy policy = new WebUrlPolicy(host -> List.of(InetAddress.getByName("93.184.216.34")));
        return new SafeWebHttpClient(policy, transport);
    }

    private static void assertCode(ThrowingCall call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(WebResearchException.class)
                .extracting(error -> ((WebResearchException) error).code())
                .isEqualTo(code);
    }

    private static SafeWebResponse getWithGuard(
            SafeWebHttpClient client,
            URI uri,
            Consumer<URI> guard) {
        try {
            return (SafeWebResponse) client.getClass()
                    .getMethod("get", URI.class, Consumer.class)
                    .invoke(client, uri, guard);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("SafeWebHttpClient must expose a per-target guard overload", exception);
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(exception.getCause());
        }
    }

    private static String declaredCharset(SafeWebResponse response) {
        try {
            return (String) response.getClass().getMethod("charset").invoke(response);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("SafeWebResponse must preserve the declared response charset", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<InetAddress> pinnedAddresses(WebTransportRequest request) {
        try {
            return (List<InetAddress>) request.getClass().getMethod("resolvedAddresses").invoke(request);
        } catch (ReflectiveOperationException exception) {
            return List.of();
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }

    private static final class FakeTransport implements WebHttpTransport {
        private final Queue<WebTransportResponse> responses = new ArrayDeque<>();
        private final List<WebTransportRequest> requests = new ArrayList<>();

        void add(int status, Map<String, List<String>> headers, String body) {
            addBytes(status, headers, body.getBytes(StandardCharsets.UTF_8));
        }

        void addBytes(int status, Map<String, List<String>> headers, byte[] body) {
            responses.add(new WebTransportResponse(status, headers, new ByteArrayInputStream(body)));
        }

        @Override
        public WebTransportResponse execute(WebTransportRequest request) {
            requests.add(request);
            return responses.remove();
        }
    }
}
