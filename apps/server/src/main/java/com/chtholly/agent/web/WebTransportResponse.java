package com.chtholly.agent.web;

import java.io.InputStream;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Streaming response returned by an HTTP transport implementation.
 *
 * @param statusCode HTTP status
 * @param headers response headers
 * @param body streaming response body
 */
public record WebTransportResponse(int statusCode, Map<String, List<String>> headers, InputStream body)
        implements AutoCloseable {

    /**
     * Validates and deeply copies response metadata.
     */
    public WebTransportResponse {
        Objects.requireNonNull(headers, "headers");
        Map<String, List<String>> copiedHeaders = new LinkedHashMap<>();
        headers.forEach((name, values) -> copiedHeaders.put(
                Objects.requireNonNull(name, "header name"),
                List.copyOf(Objects.requireNonNull(values, "header values"))));
        headers = Map.copyOf(copiedHeaders);
        body = Objects.requireNonNull(body, "body");
    }

    /**
     * Closes the response body.
     */
    @Override
    public void close() throws java.io.IOException {
        body.close();
    }
}
