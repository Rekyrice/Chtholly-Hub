package com.chtholly.cache.observability;

import com.chtholly.cache.config.CacheProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Exposes the cache runtime mode and origin-load counters used by the benchmark harness. */
@Component
public class CacheMetrics {

    private final Counter mysqlQueries;
    private final Counter sameKeyLoads;

    public CacheMetrics(MeterRegistry registry, CacheProperties properties) {
        String readMode = properties.getReadMode().externalName();
        String singleFlightEnabled = String.valueOf(properties.getReadMode().usesSingleFlight());
        Gauge.builder("chtholly.cache.runtime", () -> 1.0)
                .description("Cache benchmark runtime contract")
                .tag("read_mode", readMode)
                .tag("single_flight_enabled", singleFlightEnabled)
                .tag("cache_metrics_available", "true")
                .register(registry);
        this.mysqlQueries = Counter.builder("chtholly.cache.mysql.query")
                .description("MySQL queries issued by benchmarked cache read paths")
                .tag("read_mode", readMode)
                .register(registry);
        this.sameKeyLoads = Counter.builder("chtholly.cache.same.key.load")
                .description("Origin loads issued for the benchmarked cache key")
                .tag("read_mode", readMode)
                .register(registry);
    }

    public void recordMysqlQuery() {
        mysqlQueries.increment();
    }

    public void recordSameKeyLoad() {
        sameKeyLoads.increment();
    }
}
