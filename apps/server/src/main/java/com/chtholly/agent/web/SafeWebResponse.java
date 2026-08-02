package com.chtholly.agent.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Bounded HTTP response safe to pass to web parsers.
 *
 * @param finalUrl URL after manual redirects
 * @param statusCode final HTTP status
 * @param redirectChain requested URLs including the initial and final URL
 * @param contentType normalized media type
 * @param charset validated charset declared by the Content-Type header, or an empty string
 * @param bytes bounded body bytes
 */
public record SafeWebResponse(
        URI finalUrl,
        int statusCode,
        List<URI> redirectChain,
        String contentType,
        String charset,
        byte[] bytes) {

    /**
     * Creates a response without a declared charset.
     *
     * @param finalUrl URL after manual redirects
     * @param statusCode final HTTP status
     * @param redirectChain requested URLs including the initial and final URL
     * @param contentType normalized media type
     * @param bytes bounded body bytes
     */
    public SafeWebResponse(
            URI finalUrl,
            int statusCode,
            List<URI> redirectChain,
            String contentType,
            byte[] bytes) {
        this(finalUrl, statusCode, redirectChain, contentType, "", bytes);
    }

    /**
     * Validates and defensively copies bounded response values.
     */
    public SafeWebResponse {
        finalUrl = Objects.requireNonNull(finalUrl, "finalUrl");
        redirectChain = List.copyOf(Objects.requireNonNull(redirectChain, "redirectChain"));
        contentType = Objects.requireNonNull(contentType, "contentType");
        charset = Objects.requireNonNull(charset, "charset");
        bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    /**
     * Returns a defensive copy of the bounded body.
     *
     * @return copied response bytes
     */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * Decodes the bounded body as UTF-8.
     *
     * @return UTF-8 body text
     */
    public String bodyAsUtf8() {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
