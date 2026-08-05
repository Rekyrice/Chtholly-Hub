package com.chtholly.agent.ws;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies heartbeat resources are released during bean destruction. */
class AgentWebSocketHeartbeatLifecycleTest {

    @Test
    void shutdownStopsSchedulerAndClearsTrackedSessions()
            throws Exception {
        AgentWebSocketHeartbeat heartbeat = new AgentWebSocketHeartbeat();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("heartbeat-session");
        heartbeat.start(session);

        assertDoesNotThrow(heartbeat::shutdown);
        assertDoesNotThrow(heartbeat::shutdown);

        assertThat(scheduler(heartbeat).isShutdown()).isTrue();
        assertThat(sessions(heartbeat)).isEmpty();
    }

    @Test
    void failedHeartbeatSchedulingRollsBackTrackedSession()
            throws Exception {
        ScheduledExecutorService scheduler =
                mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(
                any(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new java.util.concurrent.RejectedExecutionException(
                        "scheduler stopped"));
        AgentWebSocketHeartbeat heartbeat =
                new AgentWebSocketHeartbeat(scheduler);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("schedule-failure");

        assertThrows(
                java.util.concurrent.RejectedExecutionException.class,
                () -> heartbeat.start(session));

        assertThat(sessions(heartbeat)).isEmpty();
    }

    @Test
    void pingSendFailureClosesTheSession() throws Exception {
        ScheduledExecutorService scheduler =
                mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        when(scheduler.scheduleAtFixedRate(
                any(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    Runnable ping = invocation.getArgument(0);
                    ping.run();
                    return future;
                });
        AgentWebSocketHeartbeat heartbeat =
                new AgentWebSocketHeartbeat(scheduler);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ping-failure");
        when(session.isOpen()).thenReturn(true);
        doThrow(new java.io.IOException("socket unavailable"))
                .when(session).sendMessage(any());

        heartbeat.start(session);

        verify(session).close(any());
        assertThat(sessions(heartbeat)).isEmpty();
    }

    private static ScheduledExecutorService scheduler(
            AgentWebSocketHeartbeat heartbeat) throws Exception {
        Field field = AgentWebSocketHeartbeat.class
                .getDeclaredField("scheduler");
        field.setAccessible(true);
        return (ScheduledExecutorService) field.get(heartbeat);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> sessions(
            AgentWebSocketHeartbeat heartbeat) throws Exception {
        Field field = AgentWebSocketHeartbeat.class
                .getDeclaredField("sessions");
        field.setAccessible(true);
        return (Map<String, ?>) field.get(heartbeat);
    }
}
