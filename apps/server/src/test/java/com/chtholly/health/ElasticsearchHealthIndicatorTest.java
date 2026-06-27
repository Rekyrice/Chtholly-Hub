package com.chtholly.health;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import co.elastic.clients.elasticsearch.cluster.ElasticsearchClusterClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ElasticsearchHealthIndicatorTest {

    @Mock
    private ElasticsearchClient client;

    @InjectMocks
    private ElasticsearchHealthIndicator indicator;

    @Test
    void health_up_whenClusterHealthy() throws Exception {
        var pingResponse = mock(co.elastic.clients.transport.endpoints.BooleanResponse.class);
        when(pingResponse.value()).thenReturn(true);
        when(client.ping()).thenReturn(pingResponse);

        HealthResponse healthResponse = mock(HealthResponse.class);
        HealthStatus status = mock(HealthStatus.class);
        when(status.jsonValue()).thenReturn("green");
        when(healthResponse.status()).thenReturn(status);
        when(healthResponse.numberOfNodes()).thenReturn(3);

        ElasticsearchClusterClient clusterClient = mock(ElasticsearchClusterClient.class);
        when(client.cluster()).thenReturn(clusterClient);
        when(clusterClient.health()).thenReturn(healthResponse);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("clusterStatus", "green");
        assertThat(health.getDetails()).containsEntry("numberOfNodes", 3);
    }

    @Test
    void health_down_whenPingFails() throws Exception {
        var pingResponse = mock(co.elastic.clients.transport.endpoints.BooleanResponse.class);
        when(pingResponse.value()).thenReturn(false);
        when(client.ping()).thenReturn(pingResponse);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "ping failed");
    }

    @Test
    void health_down_whenClientThrows() throws Exception {
        when(client.ping()).thenThrow(new RuntimeException("connection refused"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }
}
