package com.chtholly.agent.web;

import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Validates outbound research URLs and prevents DNS-based SSRF access to non-public networks.
 */
@Slf4j
public final class WebUrlPolicy {

    /** Maximum accepted URL length. */
    public static final int MAX_URL_LENGTH = 2_048;

    private final HostResolver resolver;

    /**
     * Creates a policy backed by the system DNS resolver.
     */
    public WebUrlPolicy() {
        this(host -> Arrays.asList(InetAddress.getAllByName(host)));
    }

    /**
     * Creates a policy with an injectable resolver.
     *
     * @param resolver DNS resolver used for every target host
     */
    public WebUrlPolicy(HostResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * Parses and validates a URL.
     *
     * @param value URL text
     * @return validated URI
     * @throws WebResearchException when the URL is unsafe or cannot be resolved
     */
    public URI validate(String value) {
        if (value == null || value.isBlank()) {
            throw failure("WEB_URL_INVALID", "The web address is invalid.");
        }
        if (value.length() > MAX_URL_LENGTH) {
            throw failure("WEB_URL_TOO_LONG", "The web address is too long.");
        }
        try {
            return validate(new URI(value));
        } catch (URISyntaxException exception) {
            log.debug("Rejected invalid web URI syntax", exception);
            throw new WebResearchException("WEB_URL_INVALID", "The web address is invalid.", exception);
        }
    }

    /**
     * Validates a parsed URL, including all current DNS answers.
     *
     * @param uri parsed URL
     * @return the same validated URI
     * @throws WebResearchException when the URL is unsafe or cannot be resolved
     */
    public URI validate(URI uri) {
        return validateTarget(uri).uri();
    }

    /**
     * Validates a target and returns the exact addresses approved for its connection.
     *
     * @param uri parsed URL
     * @return validated URI and immutable resolved addresses
     */
    ValidatedTarget validateTarget(URI uri) {
        if (uri == null || uri.toASCIIString().length() > MAX_URL_LENGTH) {
            throw failure(uri == null ? "WEB_URL_INVALID" : "WEB_URL_TOO_LONG",
                    uri == null ? "The web address is invalid." : "The web address is too long.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw failure("WEB_URL_SCHEME_UNSUPPORTED", "Only HTTP and HTTPS web addresses are supported.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.isOpaque()) {
            throw failure("WEB_URL_INVALID", "The web address is invalid.");
        }
        if (uri.getRawUserInfo() != null) {
            throw failure("WEB_URL_USERINFO_FORBIDDEN", "Web addresses containing credentials are not allowed.");
        }
        int port = uri.getPort();
        int defaultPort = scheme.equals("https") ? 443 : 80;
        if (port != -1 && port != defaultPort) {
            throw failure("WEB_URL_PORT_FORBIDDEN", "The web address uses a disallowed port.");
        }

        String host = unbracket(uri.getHost());
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException | RuntimeException exception) {
            log.debug("Web host DNS resolution failed", exception);
            throw new WebResearchException("WEB_URL_DNS_FAILED", "The web address could not be resolved.", exception);
        }
        if (addresses == null || addresses.isEmpty()) {
            throw failure("WEB_URL_DNS_FAILED", "The web address could not be resolved.");
        }
        if (addresses.stream().anyMatch(address -> address == null || !isPublic(address))) {
            throw failure("WEB_URL_ADDRESS_FORBIDDEN", "The web address resolves to a private or reserved network.");
        }
        return new ValidatedTarget(uri, addresses);
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address || bytes.length == 4) {
            return isPublicIpv4(bytes);
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            if (isIpv4Mapped(bytes)) {
                return isPublicIpv4(Arrays.copyOfRange(bytes, 12, 16));
            }
            // Documentation, discard-only and ORCHID prefixes are not public destinations.
            if (inPrefix(bytes, new byte[]{0x20, 0x01, 0x0d, (byte) 0xb8}, 32)
                    || inPrefix(bytes, new byte[]{0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}, 64)
                    || inPrefix(bytes, new byte[]{0x20, 0x01, 0x00, 0x00}, 32)
                    || inPrefix(bytes, new byte[]{0x20, 0x01, 0x00, 0x10}, 28)
                    || inPrefix(bytes, new byte[]{0x20, 0x01, 0x00, 0x20}, 28)
                    || inPrefix(bytes, new byte[]{0x20, 0x02}, 16)) {
                return false;
            }
            // Current globally routable unicast space. This also rejects unallocated/reserved IPv6 space.
            return (bytes[0] & 0xe0) == 0x20;
        }
        return false;
    }

    private static String unbracket(String host) {
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
    }

    private static boolean isPublicIpv4(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        int third = bytes[2] & 0xff;
        if (first == 0 || first == 10 || first == 127 || first >= 224) {
            return false;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return false;
        }
        if (first == 169 && second == 254) {
            return false;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return false;
        }
        if (first == 192 && (second == 168 || (second == 0 && third == 0)
                || (second == 0 && third == 2))) {
            return false;
        }
        if (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100))) {
            return false;
        }
        return !(first == 203 && second == 0 && third == 113);
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static boolean inPrefix(byte[] address, byte[] prefix, int bits) {
        int fullBytes = bits / 8;
        int remainingBits = bits % 8;
        for (int index = 0; index < fullBytes; index++) {
            if (index >= prefix.length || address[index] != prefix[index]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits);
        return fullBytes < prefix.length
                && ((address[fullBytes] & mask) == (prefix[fullBytes] & mask));
    }

    private static WebResearchException failure(String code, String message) {
        return new WebResearchException(code, message);
    }

    /**
     * Resolves all current addresses for a host.
     */
    @FunctionalInterface
    public interface HostResolver {

        /**
         * Resolves a host.
         *
         * @param host target host
         * @return every DNS answer for the host
         * @throws UnknownHostException when the host cannot be resolved
         */
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    /**
     * A validated URI paired with the exact DNS answers approved by this policy.
     */
    record ValidatedTarget(URI uri, List<InetAddress> addresses) {
        ValidatedTarget {
            uri = Objects.requireNonNull(uri, "uri");
            addresses = List.copyOf(Objects.requireNonNull(addresses, "addresses"));
        }
    }
}
