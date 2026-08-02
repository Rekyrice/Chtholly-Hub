package com.chtholly.agent.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdkWebHttpTransportTest {

    @Test
    void rejectsInjectedClientsThatFollowRedirects() {
        HttpClient following = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        assertThatThrownBy(() -> new JdkWebHttpTransport(following))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redirect");
    }

    @Test
    void connectsToPinnedAddressWhilePreservingOriginalHost() throws Exception {
        AtomicReference<String> host = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/pinned", exchange -> {
            host.set(exchange.getRequestHeaders().getFirst("Host"));
            byte[] body = "pinned".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            URI uri = URI.create("http://does-not-resolve.invalid:" + port + "/pinned");
            WebTransportRequest request = pinnedRequest(
                    uri, List.of(InetAddress.getLoopbackAddress()));

            try (WebTransportResponse response = new JdkWebHttpTransport().execute(request)) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body().readAllBytes()).isEqualTo("pinned".getBytes(StandardCharsets.UTF_8));
            }
            assertThat(host).hasValue("does-not-resolve.invalid:" + port);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pinnedTransportNeverFollowsRedirectsAutomatically() throws Exception {
        AtomicInteger finalCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().set("Location", "/final");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/final", exchange -> {
            finalCalls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            WebTransportRequest request = pinnedRequest(
                    URI.create("http://does-not-resolve.invalid:" + port + "/start"),
                    List.of(InetAddress.getLoopbackAddress()));

            try (WebTransportResponse response = new JdkWebHttpTransport().execute(request)) {
                assertThat(response.statusCode()).isEqualTo(302);
            }
            assertThat(finalCalls).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @SuppressWarnings("unchecked")
    private static WebTransportRequest pinnedRequest(URI uri, List<InetAddress> addresses) throws Exception {
        Constructor<WebTransportRequest> constructor;
        try {
            constructor = (Constructor<WebTransportRequest>) WebTransportRequest.class.getConstructor(
                    URI.class, Duration.class, Map.class, List.class);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("WebTransportRequest must carry validated addresses", exception);
        }
        return constructor.newInstance(uri, Duration.ofSeconds(2), Map.of(), addresses);
    }
}
