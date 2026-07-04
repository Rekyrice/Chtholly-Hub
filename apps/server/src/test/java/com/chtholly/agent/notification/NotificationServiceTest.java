package com.chtholly.agent.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-04T12:00:00Z");

    private StringRedisTemplate redis;
    private ListOperations<String, String> listOps;
    private ObjectMapper objectMapper;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(redis.opsForList()).thenReturn(listOps);
        service = new NotificationService(
                redis,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void sendStoresPendingNotificationAndPushesOnlineSession() throws Exception {
        List<Notification> delivered = new ArrayList<>();
        service.registerSession(7L, "ws-1", delivered::add);

        service.send(7L, new Notification(
                "missing-you",
                "I noticed you have not visited for a while.",
                NotificationChannel.FLOATING));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(listOps).rightPush(eq("agent:notifications:7"), payloadCaptor.capture());
        Notification stored = objectMapper.readValue(payloadCaptor.getValue(), Notification.class);
        assertThat(stored.type()).isEqualTo("missing-you");
        assertThat(stored.message()).contains("not visited");
        assertThat(stored.timestamp()).isEqualTo(NOW);
        assertThat(stored.channel()).isEqualTo(NotificationChannel.FLOATING);
        verify(listOps).trim("agent:notifications:7", -10, -1);
        verify(redis).expire("agent:notifications:7", Duration.ofDays(7));
        assertThat(delivered).containsExactly(stored);
    }

    @Test
    void getPendingNotificationsSkipsMalformedPayloads() throws Exception {
        Notification notification = new Notification(
                "thought",
                "A quiet thought.",
                NOW,
                NotificationChannel.AGENT_PAGE);
        when(listOps.range("agent:notifications:7", 0, -1)).thenReturn(List.of(
                "bad-json",
                objectMapper.writeValueAsString(notification)
        ));

        assertThat(service.getPendingNotifications(7L)).containsExactly(notification);
    }

    @Test
    void broadcastPushesToEveryRegisteredSession() {
        List<Notification> first = new ArrayList<>();
        List<Notification> second = new ArrayList<>();
        service.registerSession(1L, "ws-1", first::add);
        service.registerSession(2L, "ws-2", second::add);
        Notification notification = new Notification(
                "thought",
                "The warehouse is quiet today.",
                NotificationChannel.AGENT_PAGE);

        service.broadcast(notification);

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(first.getFirst().type()).isEqualTo("thought");
        assertThat(second.getFirst().channel()).isEqualTo(NotificationChannel.AGENT_PAGE);
    }
}
