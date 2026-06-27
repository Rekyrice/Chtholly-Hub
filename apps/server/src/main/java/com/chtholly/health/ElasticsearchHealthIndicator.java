package com.chtholly.health;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Elasticsearch 集群连通性与状态探测。 */
@Component
@RequiredArgsConstructor
public class ElasticsearchHealthIndicator implements HealthIndicator {

    private final ElasticsearchClient client;

    @Override
    public Health health() {
        return HealthCheckSupport.runWithTimeout(this::probeCluster);
    }

    private Health probeCluster() {
        try {
            if (!client.ping().value()) {
                return Health.down().withDetail("error", "ping failed").build();
            }
            HealthResponse response = client.cluster().health();
            return Health.up()
                    .withDetail("clusterStatus", response.status().jsonValue())
                    .withDetail("numberOfNodes", response.numberOfNodes())
                    .build();
        } catch (Exception e) {
            return Health.down().withException(e).build();
        }
    }
}
