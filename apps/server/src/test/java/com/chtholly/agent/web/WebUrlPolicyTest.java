package com.chtholly.agent.web;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebUrlPolicyTest {

    @Test
    void acceptsPublicHttpAndHttpsUrls() throws Exception {
        WebUrlPolicy policy = new WebUrlPolicy(host -> List.of(InetAddress.getByName("93.184.216.34")));

        assertThat(policy.validate(URI.create("https://example.com/article")))
                .isEqualTo(URI.create("https://example.com/article"));
        assertThat(policy.validate(URI.create("http://example.com:80/article")))
                .isEqualTo(URI.create("http://example.com:80/article"));
    }

    @Test
    void rejectsUnsupportedShapeBeforeDnsLookup() {
        WebUrlPolicy policy = new WebUrlPolicy(host -> List.of());

        assertCode(policy, "ftp://example.com", "WEB_URL_SCHEME_UNSUPPORTED");
        assertCode(policy, "https://user@example.com", "WEB_URL_USERINFO_FORBIDDEN");
        assertCode(policy, "https://example.com:8443", "WEB_URL_PORT_FORBIDDEN");
        assertCode(policy, "https://", "WEB_URL_INVALID");
        assertCode(policy, "https://example.com/" + "a".repeat(2030), "WEB_URL_TOO_LONG");
    }

    @Test
    void rejectsEveryPrivateOrReservedIpv4RangeAndMixedDns() throws Exception {
        List<String> blocked = List.of(
                "0.1.2.3", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.1.1",
                "172.16.0.1", "192.0.0.1", "192.0.2.1", "192.168.1.1", "198.18.0.1",
                "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255");

        for (String address : blocked) {
            WebUrlPolicy policy = new WebUrlPolicy(host -> List.of(InetAddress.getByName(address)));
            assertCode(policy, "https://example.com", "WEB_URL_ADDRESS_FORBIDDEN");
        }

        WebUrlPolicy mixed = new WebUrlPolicy(host -> List.of(
                InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1")));
        assertCode(mixed, "https://example.com", "WEB_URL_ADDRESS_FORBIDDEN");
    }

    @Test
    void rejectsPrivateReservedAndMappedIpv6() throws Exception {
        List<String> blocked = List.of("::", "::1", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1",
                "2002:7f00:1::", "::ffff:127.0.0.1");
        for (String address : blocked) {
            WebUrlPolicy policy = new WebUrlPolicy(host -> List.of(InetAddress.getByName(address)));
            assertCode(policy, "https://example.com", "WEB_URL_ADDRESS_FORBIDDEN");
        }
    }

    @Test
    void rejectsWholeOrchidV2Ipv6Prefix() throws Exception {
        List<String> blocked = List.of(
                "2001:20::",
                "2001:20::1",
                "2001:2f:ffff:ffff:ffff:ffff:ffff:ffff");

        for (String address : blocked) {
            WebUrlPolicy policy = new WebUrlPolicy(host -> List.of(InetAddress.getByName(address)));
            assertCode(policy, "https://example.com", "WEB_URL_ADDRESS_FORBIDDEN");
        }
    }

    @Test
    void doesNotTreatAdjacentIpv6PrefixAsOrchidV2() throws Exception {
        WebUrlPolicy policy = new WebUrlPolicy(
                host -> List.of(InetAddress.getByName("2001:30::1")));

        assertThat(policy.validate(URI.create("https://example.com/")))
                .isEqualTo(URI.create("https://example.com/"));
    }

    @Test
    void failsClosedWhenDnsCannotResolve() {
        WebUrlPolicy policy = new WebUrlPolicy(host -> List.of());
        assertCode(policy, "https://example.com", "WEB_URL_DNS_FAILED");
    }

    private static void assertCode(WebUrlPolicy policy, String value, String code) {
        assertThatThrownBy(() -> policy.validate(value))
                .isInstanceOf(WebResearchException.class)
                .extracting(error -> ((WebResearchException) error).code())
                .isEqualTo(code);
    }
}
