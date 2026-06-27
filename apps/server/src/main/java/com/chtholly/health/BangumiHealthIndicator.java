package com.chtholly.health;

import com.chtholly.bangumi.client.BangumiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bangumi API 可达性探测。
 * <p>
 * 结果缓存 5 分钟，避免每次 health check 触发限流。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "bangumi.enabled", havingValue = "true", matchIfMissing = true)
public class BangumiHealthIndicator implements HealthIndicator {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final BangumiClient bangumiClient;
    private final AtomicReference<CachedResult> cache = new AtomicReference<>();

    @Override
    public Health health() {
        CachedResult cached = cache.get();
        if (cached != null && !cached.isExpired()) {
            return cached.health();
        }
        Health result = HealthCheckSupport.runWithTimeout(this::probeApi);
        cache.set(new CachedResult(result, Instant.now()));
        return result;
    }

    private Health probeApi() {
        Optional<JsonNode> calendar = bangumiClient.fetchCalendar();
        if (calendar.isPresent()) {
            return Health.up().withDetail("api_reachable", true).build();
        }
        return Health.down().withDetail("error", "Bangumi API unreachable").build();
    }

    private record CachedResult(Health health, Instant checkedAt) {
        boolean isExpired() {
            return checkedAt.plus(CACHE_TTL).isBefore(Instant.now());
        }
    }
}
