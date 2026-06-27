package com.chtholly.health;

import com.chtholly.bangumi.client.BangumiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BangumiHealthIndicatorTest {

    @Mock
    private BangumiClient bangumiClient;

    @InjectMocks
    private BangumiHealthIndicator indicator;

    @Test
    void health_up_whenApiReachable() {
        when(bangumiClient.fetchCalendar())
                .thenReturn(Optional.of(new ObjectMapper().createArrayNode()));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("api_reachable", true);
    }

    @Test
    void health_down_whenApiUnreachable() {
        when(bangumiClient.fetchCalendar()).thenReturn(Optional.empty());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "Bangumi API unreachable");
    }

    @Test
    void health_usesCache_withinFiveMinutes() {
        when(bangumiClient.fetchCalendar())
                .thenReturn(Optional.of(new ObjectMapper().createArrayNode()));

        indicator.health();
        indicator.health();

        verify(bangumiClient, times(1)).fetchCalendar();
    }
}
