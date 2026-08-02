package com.chtholly.agent.web;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Applies URL, redirect, header, media type and body-size policy to outbound web requests.
 */
@Slf4j
public final class SafeWebHttpClient {

    /** Maximum response body size in bytes. */
    public static final int MAX_BODY_BYTES = 1_048_576;
    /** Maximum number of redirects. */
    public static final int MAX_REDIRECTS = 3;
    /** Timeout for each request. */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private static final Set<String> CONTENT_TYPES = Set.of(
            "text/html", "application/xhtml+xml", "text/plain");
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Map<String, String> SAFE_HEADERS = Map.of(
            "Accept-Encoding", "identity",
            "User-Agent", "ChthollyHubBot/1.0",
            "Accept", "text/html, application/xhtml+xml, text/plain");

    private final WebUrlPolicy urlPolicy;
    private final WebHttpTransport transport;

    /**
     * Creates a safe client using the JDK HTTP transport.
     *
     * @param urlPolicy outbound URL policy
     */
    public SafeWebHttpClient(WebUrlPolicy urlPolicy) {
        this(urlPolicy, new JdkWebHttpTransport());
    }

    /**
     * Creates a safe client with an injectable transport.
     *
     * @param urlPolicy outbound URL policy
     * @param transport single-hop HTTP transport
     */
    public SafeWebHttpClient(WebUrlPolicy urlPolicy, WebHttpTransport transport) {
        this.urlPolicy = Objects.requireNonNull(urlPolicy, "urlPolicy");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * Fetches a URL under the complete web safety policy.
     *
     * @param uri initial URL
     * @return bounded final response
     * @throws WebResearchException on any policy or transport failure
     */
    public SafeWebResponse get(URI uri) {
        return get(uri, ignored -> {
        });
    }

    /**
     * Fetches a URL and invokes a policy guard before every redirect hop is sent.
     *
     * <p>The guard runs after URL and DNS validation but before transport execution. Any runtime
     * exception raised by the guard is propagated unchanged.</p>
     *
     * @param uri initial URL
     * @param perTargetGuard per-hop policy guard, such as a robots evaluator
     * @return bounded final response
     * @throws WebResearchException on any URL, policy or transport failure
     */
    public SafeWebResponse get(URI uri, Consumer<URI> perTargetGuard) {
        Objects.requireNonNull(perTargetGuard, "perTargetGuard");
        WebUrlPolicy.ValidatedTarget validated = urlPolicy.validateTarget(uri);
        URI current = validated.uri();
        List<java.net.InetAddress> resolvedAddresses = validated.addresses();
        List<URI> chain = new ArrayList<>();
        chain.add(current);

        for (int redirects = 0; ; redirects++) {
            perTargetGuard.accept(current);
            try (WebTransportResponse response = transport.execute(
                    new WebTransportRequest(current, REQUEST_TIMEOUT, SAFE_HEADERS, resolvedAddresses))) {
                if (REDIRECT_STATUSES.contains(response.statusCode())) {
                    if (redirects >= MAX_REDIRECTS) {
                        throw failure("WEB_TOO_MANY_REDIRECTS", "The web page redirected too many times.");
                    }
                    String location = firstHeader(response.headers(), "location")
                            .orElseThrow(() -> failure("WEB_REDIRECT_INVALID", "The web page returned an invalid redirect."));
                    URI next;
                    try {
                        next = current.resolve(location);
                    } catch (IllegalArgumentException exception) {
                        log.debug("Web response contained an invalid redirect", exception);
                        throw new WebResearchException(
                                "WEB_REDIRECT_INVALID", "The web page returned an invalid redirect.", exception);
                    }
                    if ("https".equalsIgnoreCase(current.getScheme())
                            && "http".equalsIgnoreCase(next.getScheme())) {
                        throw failure("WEB_REDIRECT_DOWNGRADE", "A secure web page attempted an insecure redirect.");
                    }
                    validated = urlPolicy.validateTarget(next);
                    current = validated.uri();
                    resolvedAddresses = validated.addresses();
                    chain.add(current);
                    continue;
                }

                ParsedContentType parsedContentType = parseContentType(
                        firstHeader(response.headers(), "content-type").orElse(""));
                String contentType = parsedContentType.mediaType();
                String contentEncoding = firstHeader(response.headers(), "content-encoding")
                        .orElse("identity").trim();
                if (!contentEncoding.isEmpty() && !contentEncoding.equalsIgnoreCase("identity")) {
                    throw failure("WEB_CONTENT_ENCODING_UNSUPPORTED",
                            "The web page uses an unsupported content encoding.");
                }
                long contentLength = parseContentLength(firstHeader(response.headers(), "content-length").orElse(null));
                if (contentLength > MAX_BODY_BYTES) {
                    throw failure("WEB_BODY_TOO_LARGE", "The web page is too large to process safely.");
                }
                byte[] bytes = readBounded(response.body());
                if (!bytesIsEmpty(bytes) && !CONTENT_TYPES.contains(contentType)) {
                    throw failure("WEB_CONTENT_TYPE_UNSUPPORTED", "The web page uses an unsupported content type.");
                }
                return new SafeWebResponse(
                        current,
                        response.statusCode(),
                        List.copyOf(chain),
                        contentType,
                        parsedContentType.charset(),
                        bytes);
            } catch (WebResearchException exception) {
                log.debug("Web request rejected with code {}", exception.code());
                throw exception;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                log.debug("Web request was interrupted", exception);
                throw new WebResearchException("WEB_REQUEST_INTERRUPTED", "The web request was interrupted.", exception);
            } catch (IOException | RuntimeException exception) {
                log.debug("Web request transport failed", exception);
                throw new WebResearchException("WEB_REQUEST_FAILED", "The web page could not be fetched.", exception);
            }
        }
    }

    /**
     * Parses and fetches a URL under the complete web safety policy.
     *
     * @param url initial URL text
     * @return bounded final response
     */
    public SafeWebResponse get(String url) {
        return get(urlPolicy.validate(url));
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                throw failure("WEB_BODY_TOO_LARGE", "The web page is too large to process safely.");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static Optional<String> firstHeader(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst();
    }

    private static ParsedContentType parseContentType(String value) {
        String[] segments = value.split(";", -1);
        String mediaType = segments[0].trim().toLowerCase(Locale.ROOT);
        String charset = "";
        for (int index = 1; index < segments.length; index++) {
            String parameter = segments[index].trim();
            int assignment = parameter.indexOf('=');
            if (assignment < 0 || !parameter.substring(0, assignment).trim().equalsIgnoreCase("charset")) {
                continue;
            }
            String declared = unquote(parameter.substring(assignment + 1).trim());
            String normalized;
            try {
                normalized = Charset.forName(declared).name();
            } catch (IllegalCharsetNameException | UnsupportedCharsetException exception) {
                log.debug("Web response declared an unsupported charset", exception);
                throw new WebResearchException(
                        "WEB_CHARSET_UNSUPPORTED",
                        "The web page declares an unsupported character encoding.",
                        exception);
            }
            if (!charset.isEmpty() && !charset.equalsIgnoreCase(normalized)) {
                throw failure(
                        "WEB_CHARSET_UNSUPPORTED",
                        "The web page declares conflicting character encodings.");
            }
            charset = normalized;
        }
        return new ParsedContentType(mediaType, charset);
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    private static long parseContentLength(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative content length");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            log.debug("Web response contained an invalid Content-Length", exception);
            throw new WebResearchException(
                    "WEB_RESPONSE_INVALID", "The web server returned an invalid response.", exception);
        }
    }

    private static boolean bytesIsEmpty(byte[] bytes) {
        return bytes.length == 0;
    }

    private static WebResearchException failure(String code, String message) {
        return new WebResearchException(code, message);
    }

    private record ParsedContentType(String mediaType, String charset) {
    }
}
