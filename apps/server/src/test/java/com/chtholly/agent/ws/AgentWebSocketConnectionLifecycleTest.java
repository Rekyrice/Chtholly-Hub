package com.chtholly.agent.ws;

import com.chtholly.agent.observability.AgentMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies compensating cleanup for failed connection establishment. */
class AgentWebSocketConnectionLifecycleTest {

    @Test
    void unauthorizedSendFailureStillClosesTheRawSession()
            throws Exception {
        Fixture fixture = new Fixture();
        WebSocketSession session = fixture.session("unauthorized-open");
        when(fixture.ticketStore.consume("unauthorized-open-ticket"))
                .thenReturn(null);
        doThrow(new IOException("socket unavailable"))
                .when(fixture.protocol).sendUnauthorized(session);

        assertThrows(IOException.class, () -> fixture.lifecycle.open(session));

        verify(session).close(any());
    }

    @Test
    void ticketStoreFailureClosesTheRawSession() throws Exception {
        Fixture fixture = new Fixture();
        WebSocketSession session = fixture.session("ticket-store-failure");
        when(fixture.ticketStore.consume("ticket-store-failure-ticket"))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertDoesNotThrow(() -> fixture.lifecycle.open(session));

        verify(session).close(any());
        assertThatConnectionWasRemoved(
                fixture.registry, "ticket-store-failure");
    }

    @Test
    void authenticatedOpenFailureCompensatesEveryCompletedStep()
            throws Exception {
        Fixture fixture = new Fixture();
        WebSocketSession session = fixture.session("failed-open");
        when(fixture.ticketStore.consume("failed-open-ticket"))
                .thenReturn(7L);
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(fixture.agentMetrics).wsConnected();

        assertDoesNotThrow(() -> fixture.lifecycle.open(session));

        assertThatConnectionWasRemoved(fixture.registry, "failed-open");
        verify(fixture.extensions).onConnectionOpened(any());
        verify(fixture.heartbeat).start(any());
        verify(fixture.heartbeat).stop("failed-open");
        verify(fixture.extensions).rollbackConnectionOpen(any());
        verify(fixture.rateLimiter).removeSession("failed-open");
        verify(fixture.agentMetrics, never()).wsDisconnected();
        verify(session).close(any());
    }

    private static void assertThatConnectionWasRemoved(
            AgentWebSocketConnectionRegistry registry,
            String connectionId) {
        org.assertj.core.api.Assertions.assertThat(
                registry.findOpen(connectionId)).isEmpty();
    }

    private static final class Fixture {
        private final AgentWsTicketStore ticketStore =
                mock(AgentWsTicketStore.class);
        private final AgentWebSocketConnectionRegistry registry =
                new AgentWebSocketConnectionRegistry();
        private final AgentWebSocketProtocolCodec protocol =
                mock(AgentWebSocketProtocolCodec.class);
        private final AgentWebSocketHeartbeat heartbeat =
                mock(AgentWebSocketHeartbeat.class);
        private final AgentMetrics agentMetrics = mock(AgentMetrics.class);
        private final AgentSessionRateLimiter rateLimiter =
                mock(AgentSessionRateLimiter.class);
        private final AgentWebSocketExtensionLifecycle extensions =
                mock(AgentWebSocketExtensionLifecycle.class);
        private final AgentWebSocketConnectionLifecycle lifecycle =
                new AgentWebSocketConnectionLifecycle(
                        ticketStore,
                        registry,
                        protocol,
                        heartbeat,
                        agentMetrics,
                        rateLimiter,
                        extensions);

        private WebSocketSession session(String id) {
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn(id);
            when(session.getUri()).thenReturn(URI.create(
                    "ws://localhost/api/v1/agent/ws?ticket="
                            + id + "-ticket"));
            when(session.getAttributes()).thenReturn(
                    new ConcurrentHashMap<>());
            when(session.isOpen()).thenReturn(true);
            return session;
        }
    }
}
