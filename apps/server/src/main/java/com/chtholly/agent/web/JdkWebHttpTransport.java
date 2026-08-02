package com.chtholly.agent.web;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.util.Timeout;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * JDK HTTP transport configured to never follow redirects automatically.
 */
public final class JdkWebHttpTransport implements WebHttpTransport {

    private final HttpClient legacyClient;

    /**
     * Creates the transport with a three-second connect timeout.
     */
    public JdkWebHttpTransport() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /**
     * Creates a transport around an explicitly configured client.
     *
     * @param client JDK client; it must not follow redirects
     */
    public JdkWebHttpTransport(HttpClient client) {
        this.legacyClient = Objects.requireNonNull(client, "client");
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("HTTP transport client must disable automatic redirects");
        }
    }

    /**
     * Executes one streaming GET request.
     *
     * @param request transport request
     * @return streaming response
     * @throws IOException when the network exchange fails
     * @throws InterruptedException when interrupted
     */
    @Override
    public WebTransportResponse execute(WebTransportRequest request) throws IOException, InterruptedException {
        if (!request.resolvedAddresses().isEmpty()) {
            return executePinned(request);
        }
        return executeLegacy(request);
    }

    private WebTransportResponse executeLegacy(WebTransportRequest request) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .GET()
                .timeout(request.timeout());
        request.headers().forEach(builder::header);
        HttpResponse<InputStream> response = legacyClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        return new WebTransportResponse(response.statusCode(), response.headers().map(), response.body());
    }

    private static WebTransportResponse executePinned(WebTransportRequest request) throws IOException {
        String expectedHost = normalizedHost(request.uri().getHost());
        InetAddress[] pinnedAddresses = request.resolvedAddresses().toArray(InetAddress[]::new);
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                requireExpectedHost(host, expectedHost);
                return pinnedAddresses.clone();
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                requireExpectedHost(host, expectedHost);
                return host;
            }
        };

        Timeout timeout = Timeout.ofMilliseconds(request.timeout().toMillis());
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(3))
                .setSocketTimeout(timeout)
                .build();
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setDefaultConnectionConfig(connectionConfig)
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableRedirectHandling()
                .build();
        HttpGet outbound = new HttpGet(request.uri());
        outbound.setConfig(RequestConfig.custom().setResponseTimeout(timeout).build());
        request.headers().forEach(outbound::setHeader);

        CloseableHttpResponse response = null;
        try {
            response = client.execute(outbound);
            Map<String, List<String>> headers = copyHeaders(response.getHeaders());
            InputStream content = response.getEntity() == null
                    ? InputStream.nullInputStream() : response.getEntity().getContent();
            InputStream managedBody = managedBody(content, response, client, connectionManager);
            return new WebTransportResponse(response.getCode(), headers, managedBody);
        } catch (IOException | RuntimeException exception) {
            closeOnFailure(response, client, connectionManager);
            throw exception;
        }
    }

    private static Map<String, List<String>> copyHeaders(Header[] headers) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (Header header : headers) {
            values.computeIfAbsent(header.getName(), ignored -> new ArrayList<>()).add(header.getValue());
        }
        return values;
    }

    private static InputStream managedBody(
            InputStream content,
            CloseableHttpResponse response,
            CloseableHttpClient client,
            PoolingHttpClientConnectionManager connectionManager) {
        return new FilterInputStream(content) {
            @Override
            public void close() throws IOException {
                IOException failure = null;
                try {
                    super.close();
                } catch (IOException exception) {
                    failure = exception;
                }
                failure = closeResource(response, failure);
                failure = closeResource(client, failure);
                connectionManager.close();
                if (failure != null) {
                    throw failure;
                }
            }
        };
    }

    private static void closeOnFailure(
            CloseableHttpResponse response,
            CloseableHttpClient client,
            PoolingHttpClientConnectionManager connectionManager) {
        try {
            if (response != null) {
                response.close();
            }
        } catch (IOException ignored) {
            // The original request failure remains authoritative.
        }
        try {
            client.close();
        } catch (IOException ignored) {
            // The original request failure remains authoritative.
        }
        connectionManager.close();
    }

    private static IOException closeResource(java.io.Closeable resource, IOException failure) {
        try {
            resource.close();
        } catch (IOException exception) {
            if (failure == null) {
                return exception;
            }
            failure.addSuppressed(exception);
        }
        return failure;
    }

    private static String normalizedHost(String host) {
        String value = Objects.requireNonNull(host, "host");
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static void requireExpectedHost(String host, String expectedHost) throws UnknownHostException {
        if (!normalizedHost(host).equals(expectedHost)) {
            throw new UnknownHostException("Unvalidated outbound host: " + host);
        }
    }
}
