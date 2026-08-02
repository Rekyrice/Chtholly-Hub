package com.chtholly.agent.web;

import java.net.URI;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable transport request used by the safe HTTP client boundary.
 *
 * @param uri target URI
 * @param timeout request timeout
 * @param headers outbound headers
 * @param resolvedAddresses validated addresses that the transport must use for the connection
 */
public record WebTransportRequest(
        URI uri,
        Duration timeout,
        Map<String, String> headers,
        List<InetAddress> resolvedAddresses) {

    /**
     * Creates a request without pinned addresses for compatibility with custom transports.
     *
     * @param uri target URI
     * @param timeout request timeout
     * @param headers outbound headers
     */
    public WebTransportRequest(URI uri, Duration timeout, Map<String, String> headers) {
        this(uri, timeout, headers, List.of());
    }

    /**
     * Validates and defensively copies request values.
     */
    public WebTransportRequest {
        uri = Objects.requireNonNull(uri, "uri");
        timeout = Objects.requireNonNull(timeout, "timeout");
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        resolvedAddresses = List.copyOf(Objects.requireNonNull(resolvedAddresses, "resolvedAddresses"));
    }
}
