package com.chtholly.agent.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class RobotsPolicyServiceTest {

    @Test
    void usesSpecificAgentGroupAndLongestRuleThenCachesByOrigin() throws Exception {
        CountingTransport transport = new CountingTransport();
        transport.add(200, "User-agent: *\nDisallow: /\n\n"
                + "User-agent: ChthollyHubBot\nDisallow: /private\nAllow: /private/public\n");
        RobotsPolicyService service = service(transport);

        RobotsPolicyResult first = service.check(URI.create("https://example.com/private/public/page"));
        RobotsPolicyResult second = service.check(URI.create("https://example.com/private/secret"));

        assertThat(first.decision()).isEqualTo(RobotsDecision.ALLOW);
        assertThat(first.matchedRule()).isEqualTo("Allow: /private/public");
        assertThat(first.cacheHit()).isFalse();
        assertThat(second.decision()).isEqualTo(RobotsDecision.DENY);
        assertThat(second.cacheHit()).isTrue();
        assertThat(transport.calls).hasValue(1);
    }

    @Test
    void handlesStatusCodesAndFailsClosed() throws Exception {
        CountingTransport missing = new CountingTransport();
        missing.add(404, "");
        assertThat(service(missing).check(URI.create("https://example.com/a")).decision())
                .isEqualTo(RobotsDecision.ALLOW);

        CountingTransport forbidden = new CountingTransport();
        forbidden.add(403, "");
        assertThat(service(forbidden).check(URI.create("https://example.com/a")).decision())
                .isEqualTo(RobotsDecision.DENY);

        CountingTransport malformed = new CountingTransport();
        malformed.add(200, "not a robots document");
        RobotsPolicyResult result = service(malformed).check(URI.create("https://example.com/a"));
        assertThat(result.decision()).isEqualTo(RobotsDecision.DENY);
        assertThat(result.errorCode()).isEqualTo("WEB_ROBOTS_PARSE_FAILED");
    }

    @Test
    void treatsEmptyDocumentAsAllowAllAndFormatsIpv6Origin() throws Exception {
        CountingTransport transport = new CountingTransport();
        transport.add(200, "");
        WebUrlPolicy policy = new WebUrlPolicy(host -> List.of(
                InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")));
        RobotsPolicyService service = new RobotsPolicyService(new SafeWebHttpClient(policy, transport));

        RobotsPolicyResult result = service.check(
                URI.create("https://[2606:2800:220:1:248:1893:25c8:1946]/article"));

        assertThat(result.decision()).isEqualTo(RobotsDecision.ALLOW);
        assertThat(transport.lastRequest.uri().toString())
                .isEqualTo("https://[2606:2800:220:1:248:1893:25c8:1946]/robots.txt");
    }

    @Test
    void reportsStableErrorForInvalidTarget() throws Exception {
        assertThatThrownBy(() -> service(new CountingTransport()).check(null))
                .isInstanceOf(WebResearchException.class)
                .extracting(error -> ((WebResearchException) error).code())
                .isEqualTo("WEB_URL_INVALID");
    }

    @Test
    void honorsEndAnchoredAllowRules() throws Exception {
        CountingTransport transport = new CountingTransport();
        transport.add(200, "User-agent: *\nDisallow: /private\nAllow: /private/public$\n");

        RobotsPolicyResult result = service(transport).check(
                URI.create("https://example.com/private/public/secret"));

        assertThat(result.decision()).isEqualTo(RobotsDecision.DENY);
        assertThat(result.matchedRule()).isEqualTo("Disallow: /private");
    }

    @Test
    void normalizesPercentEncodedUnreservedOctetsBeforeMatchingRules() throws Exception {
        CountingTransport transport = new CountingTransport();
        transport.add(200, "User-agent: *\nDisallow: /private\n");

        RobotsPolicyResult result = service(transport).check(
                URI.create("https://example.com/%70rivate/secret"));

        assertThat(result.decision()).isEqualTo(RobotsDecision.DENY);
        assertThat(result.matchedRule()).isEqualTo("Disallow: /private");
    }

    @Test
    void matchesWildcardRulesWithoutRegexBacktracking() throws Exception {
        CountingTransport transport = new CountingTransport();
        transport.add(200, "User-agent: *\nDisallow: /a*a*a*a*a*a*b\n");
        RobotsPolicyService service = service(transport);

        RobotsPolicyResult result = assertTimeout(
                Duration.ofMillis(500),
                () -> service.check(URI.create("https://example.com/" + "a".repeat(80))));

        assertThat(result.decision()).isEqualTo(RobotsDecision.ALLOW);
    }

    @Test
    void wildcardAndEndAnchorPreserveRobotsMatchingSemantics() throws Exception {
        CountingTransport transport = new CountingTransport();
        transport.add(200, "User-agent: *\nDisallow: /folder/*/private$\n");
        RobotsPolicyService service = service(transport);

        RobotsPolicyResult exact = service.check(URI.create("https://example.com/folder/x/private"));
        RobotsPolicyResult suffix = service.check(URI.create("https://example.com/folder/x/private/more"));

        assertThat(exact.decision()).isEqualTo(RobotsDecision.DENY);
        assertThat(suffix.decision()).isEqualTo(RobotsDecision.ALLOW);
    }

    @Test
    void percentEncodesUnicodeRulePathsBeforeMatchingRawUris() throws Exception {
        CountingTransport transport = new CountingTransport();
        transport.add(200, "User-agent: *\nDisallow: /秘密/🌙\n");

        RobotsPolicyResult result = service(transport).check(URI.create(
                "https://example.com/%E7%A7%98%E5%AF%86/%F0%9F%8C%99"));

        assertThat(result.decision()).isEqualTo(RobotsDecision.DENY);
        assertThat(result.matchedRule()).isEqualTo("Disallow: /秘密/🌙");
    }

    @Test
    void evictsLeastRecentlyUsedOriginsAtTheCacheLimit() throws Exception {
        StaticTransport transport = new StaticTransport();
        RobotsPolicyService service = service(transport);

        for (int index = 0; index <= 256; index++) {
            service.check(URI.create("https://host" + index + ".example/article"));
        }
        service.check(URI.create("https://host0.example/another"));

        assertThat(transport.calls).hasValue(258);
    }

    @Test
    void coalescesConcurrentLoadsForTheSameOrigin() throws Exception {
        BlockingTransport transport = new BlockingTransport();
        RobotsPolicyService service = service(transport);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RobotsPolicyResult> first = executor.submit(() -> {
                start.await();
                return service.check(URI.create("https://example.com/one"));
            });
            Future<RobotsPolicyResult> second = executor.submit(() -> {
                start.await();
                return service.check(URI.create("https://example.com/two"));
            });
            start.countDown();
            assertThat(transport.entered.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(transport.calls).hasValue(1);
            transport.release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS).allowed()).isTrue();
            assertThat(second.get(1, TimeUnit.SECONDS).allowed()).isTrue();
        } finally {
            transport.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failsClosedWhenRobotsDocumentExceedsCharacterLimit() throws Exception {
        assertRobotsParseFailed("User-agent: *\n#" + "x".repeat(131_072));
    }

    @Test
    void failsClosedWhenRobotsDocumentContainsTooManyRules() throws Exception {
        StringBuilder body = new StringBuilder("User-agent: *\n");
        for (int index = 0; index <= 2_048; index++) {
            body.append("Disallow: /path-").append(index).append('\n');
        }
        assertRobotsParseFailed(body.toString());
    }

    @Test
    void failsClosedWhenRobotsPatternExceedsLengthLimit() throws Exception {
        assertRobotsParseFailed("User-agent: *\nDisallow: /" + "x".repeat(2_048));
    }

    private static RobotsPolicyService service(WebHttpTransport transport) throws Exception {
        WebUrlPolicy policy = new WebUrlPolicy(host -> List.of(InetAddress.getByName("93.184.216.34")));
        return new RobotsPolicyService(new SafeWebHttpClient(policy, transport),
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC));
    }

    private static void assertRobotsParseFailed(String body) throws Exception {
        CountingTransport transport = new CountingTransport();
        transport.add(200, body);

        RobotsPolicyResult result = service(transport).check(URI.create("https://example.com/article"));

        assertThat(result.decision()).isEqualTo(RobotsDecision.DENY);
        assertThat(result.errorCode()).isEqualTo("WEB_ROBOTS_PARSE_FAILED");
    }

    private static final class CountingTransport implements WebHttpTransport {
        private final Queue<WebTransportResponse> responses = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();
        private WebTransportRequest lastRequest;

        void add(int status, String body) {
            responses.add(new WebTransportResponse(status,
                    body.isEmpty() ? Map.of() : Map.of("content-type", List.of("text/plain")),
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
        }

        @Override
        public WebTransportResponse execute(WebTransportRequest request) throws InterruptedException {
            calls.incrementAndGet();
            lastRequest = request;
            return responses.remove();
        }
    }

    private static class StaticTransport implements WebHttpTransport {
        protected final AtomicInteger calls = new AtomicInteger();

        @Override
        public WebTransportResponse execute(WebTransportRequest request) throws InterruptedException {
            calls.incrementAndGet();
            return new WebTransportResponse(404, Map.of(), new ByteArrayInputStream(new byte[0]));
        }
    }

    private static final class BlockingTransport extends StaticTransport {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public WebTransportResponse execute(WebTransportRequest request) throws InterruptedException {
            calls.incrementAndGet();
            entered.countDown();
            release.await();
            return new WebTransportResponse(404, Map.of(), new ByteArrayInputStream(new byte[0]));
        }
    }
}
