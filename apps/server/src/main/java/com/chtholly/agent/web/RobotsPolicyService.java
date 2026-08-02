package com.chtholly.agent.web;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fetches, parses and caches robots.txt policy independently for each web origin.
 */
@Slf4j
public final class RobotsPolicyService {

    /** Origin policy cache lifetime. */
    public static final Duration CACHE_TTL = Duration.ofMinutes(10);
    /** Maximum number of origins retained in the local policy cache. */
    public static final int MAX_CACHE_ENTRIES = 256;
    /** Maximum accepted robots document length in decoded characters. */
    public static final int MAX_DOCUMENT_CHARS = 131_072;
    /** Maximum number of rules retained from one robots document. */
    public static final int MAX_RULES = 2_048;
    /** Maximum length of one robots allow or disallow pattern. */
    public static final int MAX_PATTERN_CHARS = 2_048;

    private static final String BOT = "chthollyhubbot";

    private final SafeWebHttpClient client;
    private final Clock clock;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final AtomicLong accessSequence = new AtomicLong();
    private final Object evictionMonitor = new Object();

    /**
     * Creates a robots policy service using the system clock.
     *
     * @param client safe web client used to retrieve robots.txt
     */
    public RobotsPolicyService(SafeWebHttpClient client) {
        this(client, Clock.systemUTC());
    }

    /**
     * Creates a robots policy service with an injectable clock.
     *
     * @param client safe web client used to retrieve robots.txt
     * @param clock cache clock
     */
    public RobotsPolicyService(SafeWebHttpClient client, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Determines whether a target URL may be fetched.
     *
     * @param target target page URL
     * @return explainable robots decision
     */
    public RobotsPolicyResult check(URI target) {
        if (target == null) {
            throw new WebResearchException("WEB_URL_INVALID", "The web address is invalid.");
        }
        String origin = origin(target);
        AtomicBoolean cacheHit = new AtomicBoolean();
        CacheEntry entry = cache.compute(origin, (ignored, existing) -> {
            Instant now = clock.instant();
            long accessOrder = accessSequence.incrementAndGet();
            if (existing != null && now.isBefore(existing.expiresAt())) {
                cacheHit.set(true);
                return existing.withAccessOrder(accessOrder);
            }
            return new CacheEntry(load(origin), now.plus(CACHE_TTL), accessOrder);
        });
        if (!cacheHit.get()) {
            trimCache();
        }
        return entry.policy().evaluate(target, cacheHit.get());
    }

    /**
     * Alias for {@link #check(URI)}.
     *
     * @param target target page URL
     * @return explainable robots decision
     */
    public RobotsPolicyResult evaluate(URI target) {
        return check(target);
    }

    private CachedPolicy load(String origin) {
        SafeWebResponse response;
        try {
            response = client.get(URI.create(origin + "/robots.txt"));
        } catch (WebResearchException exception) {
            log.debug("robots.txt fetch failed with code {}", exception.code());
            return CachedPolicy.error(exception.code());
        } catch (RuntimeException exception) {
            log.debug("robots.txt fetch failed unexpectedly", exception);
            return CachedPolicy.error("WEB_ROBOTS_FETCH_FAILED");
        }

        if (response.statusCode() == 404 || response.statusCode() == 410) {
            return CachedPolicy.allowAll();
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return CachedPolicy.denyAll(null);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return CachedPolicy.error("WEB_ROBOTS_FETCH_FAILED");
        }
        try {
            return parse(response.bodyAsUtf8());
        } catch (RuntimeException exception) {
            log.debug("robots.txt parsing failed", exception);
            return CachedPolicy.error("WEB_ROBOTS_PARSE_FAILED");
        }
    }

    private static CachedPolicy parse(String body) {
        if (body.length() > MAX_DOCUMENT_CHARS) {
            throw new IllegalArgumentException("robots document exceeds character limit");
        }
        if (body.isBlank()) {
            return CachedPolicy.allowAll();
        }
        List<Group> groups = new ArrayList<>();
        List<String> agents = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        boolean sawDirective = false;
        boolean sawRules = false;
        int ruleCount = 0;

        for (String rawLine : body.split("\\R", -1)) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                if (!agents.isEmpty() && !rules.isEmpty()) {
                    groups.add(new Group(List.copyOf(agents), List.copyOf(rules)));
                    agents.clear();
                    rules.clear();
                    sawRules = false;
                }
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String field = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if (field.equals("user-agent")) {
                sawDirective = true;
                if (sawRules && !agents.isEmpty()) {
                    groups.add(new Group(List.copyOf(agents), List.copyOf(rules)));
                    agents.clear();
                    rules.clear();
                    sawRules = false;
                }
                if (!value.isEmpty()) {
                    agents.add(value.toLowerCase(Locale.ROOT));
                }
            } else if ((field.equals("allow") || field.equals("disallow")) && !agents.isEmpty()) {
                sawDirective = true;
                sawRules = true;
                if (!value.isEmpty()) {
                    if (value.length() > MAX_PATTERN_CHARS) {
                        throw new IllegalArgumentException("robots pattern exceeds length limit");
                    }
                    ruleCount++;
                    if (ruleCount > MAX_RULES) {
                        throw new IllegalArgumentException("robots document contains too many rules");
                    }
                    rules.add(new Rule(field.equals("allow"), value));
                }
            }
        }
        if (!agents.isEmpty()) {
            groups.add(new Group(List.copyOf(agents), List.copyOf(rules)));
        }
        if (!sawDirective) {
            throw new IllegalArgumentException("robots document contains no directives");
        }

        List<Rule> exact = groups.stream()
                .filter(group -> group.agents().stream().anyMatch(agent -> agent.equals(BOT)))
                .flatMap(group -> group.rules().stream())
                .toList();
        if (!exact.isEmpty() || groups.stream().anyMatch(
                group -> group.agents().stream().anyMatch(agent -> agent.equals(BOT)))) {
            return new CachedPolicy(exact, null, null);
        }
        List<Rule> wildcard = groups.stream()
                .filter(group -> group.agents().contains("*"))
                .flatMap(group -> group.rules().stream())
                .toList();
        return new CachedPolicy(wildcard, null, null);
    }

    private static String stripComment(String line) {
        int comment = line.indexOf('#');
        return comment >= 0 ? line.substring(0, comment) : line;
    }

    private static String normalizePercentEncoding(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current > 0x7F) {
                int codePoint = value.codePointAt(index);
                byte[] encoded = new String(Character.toChars(codePoint))
                        .getBytes(StandardCharsets.UTF_8);
                for (byte octet : encoded) {
                    int unsigned = octet & 0xFF;
                    normalized.append('%')
                            .append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)))
                            .append(Character.toUpperCase(Character.forDigit(unsigned & 0x0F, 16)));
                }
                index += Character.charCount(codePoint) - 1;
                continue;
            }
            if (current != '%' || index + 2 >= value.length()) {
                normalized.append(current);
                continue;
            }
            int high = Character.digit(value.charAt(index + 1), 16);
            int low = Character.digit(value.charAt(index + 2), 16);
            if (high < 0 || low < 0) {
                normalized.append(current);
                continue;
            }
            char decoded = (char) ((high << 4) + low);
            if (isUnreserved(decoded)) {
                normalized.append(decoded);
            } else {
                normalized.append('%')
                        .append(Character.toUpperCase(value.charAt(index + 1)))
                        .append(Character.toUpperCase(value.charAt(index + 2)));
            }
            index += 2;
        }
        return normalized.toString();
    }

    private static boolean isUnreserved(char value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '-'
                || value == '.'
                || value == '_'
                || value == '~';
    }

    private static String origin(URI target) {
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        String host = target.getHost();
        if ((!scheme.equals("http") && !scheme.equals("https")) || host == null) {
            throw new WebResearchException("WEB_URL_INVALID", "The web address is invalid.");
        }
        int port = target.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80)
                || (scheme.equals("https") && port == 443);
        String normalizedHost = host.startsWith("[") && host.endsWith("]")
                ? host : (host.contains(":") ? "[" + host + "]" : host);
        return scheme + "://" + normalizedHost.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }

    private void trimCache() {
        synchronized (evictionMonitor) {
            Instant now = clock.instant();
            cache.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
            while (cache.size() > MAX_CACHE_ENTRIES) {
                Map.Entry<String, CacheEntry> oldest = cache.entrySet().stream()
                        .min(Map.Entry.comparingByValue(
                                Comparator.comparingLong(CacheEntry::accessOrder)))
                        .orElse(null);
                if (oldest == null) {
                    return;
                }
                cache.remove(oldest.getKey(), oldest.getValue());
            }
        }
    }

    private record CacheEntry(CachedPolicy policy, Instant expiresAt, long accessOrder) {
        private CacheEntry withAccessOrder(long value) {
            return new CacheEntry(policy, expiresAt, value);
        }
    }

    private record Group(List<String> agents, List<Rule> rules) {
    }

    private record Rule(boolean allow, String pattern, String matchPattern, int matchLength) {
        private Rule(boolean allow, String pattern) {
            this(
                    allow,
                    pattern,
                    prepareMatchPattern(pattern),
                    normalizePercentEncoding(pattern).replace("*", "").replace("$", "").length());
        }

        private boolean matches(String path) {
            return wildcardMatches(matchPattern, path);
        }

        private static String prepareMatchPattern(String pattern) {
            String normalized = normalizePercentEncoding(pattern);
            boolean endAnchored = normalized.endsWith("$");
            String source = endAnchored
                    ? normalized.substring(0, normalized.length() - 1)
                    : normalized;
            StringBuilder collapsed = new StringBuilder(source.length() + 1);
            boolean previousWildcard = false;
            for (int index = 0; index < source.length(); index++) {
                char current = source.charAt(index);
                if (current == '*') {
                    if (!previousWildcard) {
                        collapsed.append(current);
                    }
                    previousWildcard = true;
                } else {
                    collapsed.append(current);
                    previousWildcard = false;
                }
            }
            if (!endAnchored && (collapsed.isEmpty() || collapsed.charAt(collapsed.length() - 1) != '*')) {
                collapsed.append('*');
            }
            return collapsed.toString();
        }

        private static boolean wildcardMatches(String pattern, String value) {
            int patternIndex = 0;
            int valueIndex = 0;
            int wildcardIndex = -1;
            int wildcardValueIndex = -1;
            while (valueIndex < value.length()) {
                if (patternIndex < pattern.length()
                        && pattern.charAt(patternIndex) != '*'
                        && pattern.charAt(patternIndex) == value.charAt(valueIndex)) {
                    patternIndex++;
                    valueIndex++;
                    continue;
                }
                if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                    wildcardIndex = patternIndex++;
                    wildcardValueIndex = valueIndex;
                    continue;
                }
                if (wildcardIndex >= 0) {
                    patternIndex = wildcardIndex + 1;
                    valueIndex = ++wildcardValueIndex;
                    continue;
                }
                return false;
            }
            while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                patternIndex++;
            }
            return patternIndex == pattern.length();
        }

        private String display() {
            return (allow ? "Allow: " : "Disallow: ") + pattern;
        }
    }

    private record CachedPolicy(List<Rule> rules, RobotsDecision fixedDecision, String errorCode) {
        private static CachedPolicy allowAll() {
            return new CachedPolicy(List.of(), RobotsDecision.ALLOW, null);
        }

        private static CachedPolicy denyAll(String errorCode) {
            return new CachedPolicy(List.of(), RobotsDecision.DENY, errorCode);
        }

        private static CachedPolicy error(String errorCode) {
            String code = errorCode != null && errorCode.startsWith("WEB_ROBOTS_")
                    ? errorCode : "WEB_ROBOTS_FETCH_FAILED";
            return denyAll(code);
        }

        private RobotsPolicyResult evaluate(URI target, boolean cacheHit) {
            if (fixedDecision != null) {
                return new RobotsPolicyResult(fixedDecision, cacheHit, null, errorCode);
            }
            String path = target.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if (target.getRawQuery() != null) {
                path += "?" + target.getRawQuery();
            }
            String evaluatedPath = normalizePercentEncoding(path);
            Rule matched = rules.stream()
                    .filter(rule -> rule.matches(evaluatedPath))
                    .max(Comparator.comparingInt(Rule::matchLength)
                            .thenComparing(Rule::allow))
                    .orElse(null);
            if (matched == null) {
                return new RobotsPolicyResult(RobotsDecision.ALLOW, cacheHit, null, null);
            }
            return new RobotsPolicyResult(matched.allow() ? RobotsDecision.ALLOW : RobotsDecision.DENY,
                    cacheHit, matched.display(), null);
        }
    }
}
