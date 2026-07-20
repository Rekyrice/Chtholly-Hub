package com.chtholly.cache.observability;

import com.chtholly.cache.config.CacheProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CacheMetricsTest {

    @Test
    void exposesEffectiveModeAndOriginLoadCountersWithLowCardinalityTags() {
        CacheProperties properties = new CacheProperties();
        properties.setReadMode(CacheProperties.ReadMode.FULL_NO_SINGLEFLIGHT);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheMetrics metrics = new CacheMetrics(registry, properties);

        metrics.recordMysqlQuery();
        metrics.recordSameKeyLoad();

        assertThat(registry.get("chtholly.cache.runtime")
                .tag("read_mode", "full-no-singleflight")
                .tag("single_flight_enabled", "false")
                .tag("cache_metrics_available", "true")
                .gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("chtholly.cache.mysql.query")
                .tag("read_mode", "full-no-singleflight")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("chtholly.cache.same.key.load")
                .tag("read_mode", "full-no-singleflight")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .noneMatch(tag -> tag.getKey().contains("post_id") || tag.getKey().contains("key")));
    }
}
