package com.chtholly.agent.tools;

import com.chtholly.agent.runtime.AgentToolExecutionException;
import com.chtholly.common.ratelimit.RateLimitDimension;
import com.chtholly.common.ratelimit.RateLimitKeys;
import com.chtholly.common.ratelimit.RateLimitResult;
import com.chtholly.common.ratelimit.RateLimiter;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Fixed, configuration-free web research quotas shared by the public web tools. */
final class WebResearchRateLimits {

    private static final int WINDOW_SECONDS = 60;
    private static final int SEARCH_PER_USER = 10;
    private static final int FETCH_PER_USER = 20;
    private static final int PROVIDER_OR_HOST_GLOBAL = 30;

    private WebResearchRateLimits() {
    }

    static void acquireSearch(RateLimiter limiter, long userId) {
        acquire(
                limiter,
                RateLimitDimension.USER,
                Long.toString(userId),
                "agent:web_search:user",
                SEARCH_PER_USER,
                "WEB_SEARCH_RATE_LIMITED",
                "你的网页搜索请求过于频繁，请在 %d 秒后重试。");
        acquire(
                limiter,
                RateLimitDimension.IDENTIFIER,
                "duckduckgo_html",
                "agent:web_search:provider",
                PROVIDER_OR_HOST_GLOBAL,
                "WEB_PROVIDER_RATE_LIMITED",
                "网页搜索服务请求过于频繁，请在 %d 秒后重试。");
    }

    static void acquireFetch(RateLimiter limiter, long userId, String host) {
        acquireFetchUser(limiter, userId);
        acquireFetchHost(limiter, host);
    }

    static void acquireFetchUser(RateLimiter limiter, long userId) {
        acquire(
                limiter,
                RateLimitDimension.USER,
                Long.toString(userId),
                "agent:web_fetch:user",
                FETCH_PER_USER,
                "WEB_FETCH_RATE_LIMITED",
                "你的网页抓取请求过于频繁，请在 %d 秒后重试。");
    }

    static void acquireFetchHost(RateLimiter limiter, String host) {
        acquire(
                limiter,
                RateLimitDimension.IDENTIFIER,
                Objects.requireNonNull(host, "host").toLowerCase(Locale.ROOT),
                "agent:web_fetch:host",
                PROVIDER_OR_HOST_GLOBAL,
                "WEB_HOST_RATE_LIMITED",
                "该网站的抓取请求过于频繁，请在 %d 秒后重试。");
    }

    private static void acquire(
            RateLimiter limiter,
            RateLimitDimension dimension,
            String identifier,
            String limitKey,
            int maximum,
            String deniedCode,
            String deniedMessage) {
        RateLimitResult result;
        try {
            String redisKey = RateLimitKeys.build(dimension, identifier, limitKey, WINDOW_SECONDS);
            result = Objects.requireNonNull(limiter, "limiter")
                    .tryAcquire(redisKey, maximum, WINDOW_SECONDS);
        } catch (RuntimeException exception) {
            throw new AgentToolExecutionException(
                    "WEB_RATE_LIMIT_UNAVAILABLE",
                    "网页调研限流服务暂时不可用，请稍后重试。",
                    Map.of(
                            "rateLimitDimension", dimension.name(),
                            "rateLimitIdentifier", identifier,
                            "rateLimitKey", limitKey,
                            "rateLimitMaximum", maximum,
                            "rateLimitWindowSeconds", WINDOW_SECONDS,
                            "rateLimitFailureClass", exception.getClass().getName()),
                    exception);
        }
        if (result == null) {
            throw new AgentToolExecutionException(
                    "WEB_RATE_LIMIT_UNAVAILABLE",
                    "网页调研限流服务暂时不可用，请稍后重试。",
                    Map.of(
                            "rateLimitDimension", dimension.name(),
                            "rateLimitIdentifier", identifier,
                            "rateLimitKey", limitKey,
                            "rateLimitMaximum", maximum,
                            "rateLimitWindowSeconds", WINDOW_SECONDS,
                            "rateLimitResult", "null"));
        }
        if (!result.permitted()) {
            throw new AgentToolExecutionException(
                    deniedCode,
                    deniedMessage.formatted(result.retryAfterSeconds()),
                    Map.of(
                            "rateLimitDimension", dimension.name(),
                            "rateLimitIdentifier", identifier,
                            "rateLimitKey", limitKey,
                            "rateLimitMaximum", maximum,
                            "rateLimitWindowSeconds", WINDOW_SECONDS,
                            "retryAfterSeconds", result.retryAfterSeconds()));
        }
    }
}
