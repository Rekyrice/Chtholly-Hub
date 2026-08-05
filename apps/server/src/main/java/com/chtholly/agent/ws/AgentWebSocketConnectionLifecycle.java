package com.chtholly.agent.ws;

import com.chtholly.agent.observability.AgentMetrics;
import com.chtholly.common.tracing.CorrelationIdSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Owns Agent WebSocket authentication, open, pong, and close callbacks.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentWebSocketConnectionLifecycle {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;

    private final AgentWsTicketStore ticketStore;
    private final AgentWebSocketConnectionRegistry registry;
    private final AgentWebSocketProtocolCodec protocol;
    private final AgentWebSocketHeartbeat heartbeat;
    private final AgentMetrics agentMetrics;
    private final AgentSessionRateLimiter rateLimiter;
    private final AgentWebSocketExtensionLifecycle extensions;

    /**
     * Creates the connection lifecycle boundary.
     *
     * @param ticketStore one-time WebSocket ticket store
     * @param registry connection resource registry
     * @param protocol WebSocket protocol codec
     * @param heartbeat heartbeat coordinator
     * @param agentMetrics Agent metrics recorder
     * @param rateLimiter per-connection message rate limiter
     * @param extensions optional extension lifecycle
     */
    public AgentWebSocketConnectionLifecycle(
            AgentWsTicketStore ticketStore,
            AgentWebSocketConnectionRegistry registry,
            AgentWebSocketProtocolCodec protocol,
            AgentWebSocketHeartbeat heartbeat,
            AgentMetrics agentMetrics,
            AgentSessionRateLimiter rateLimiter,
            AgentWebSocketExtensionLifecycle extensions) {
        this.ticketStore = Objects.requireNonNull(ticketStore, "ticketStore");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
        this.agentMetrics = Objects.requireNonNull(
                agentMetrics, "agentMetrics");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    void open(WebSocketSession session) throws Exception {
        Long userId;
        try {
            userId = authenticate(session);
        } catch (RuntimeException exception) {
            log.warn(
                    "Agent WebSocket authentication failed sessionId={}: {}",
                    session.getId(),
                    exception.getMessage());
            closeRawSession(
                    session,
                    CloseStatus.SERVER_ERROR.withReason(
                            "Agent authentication unavailable"));
            return;
        }
        if (userId == null) {
            try {
                protocol.sendUnauthorized(session);
            } finally {
                closeRawSession(
                        session,
                        CloseStatus.NOT_ACCEPTABLE.withReason(
                                "Unauthorized"));
            }
            return;
        }

        String correlationId = CorrelationIdSupport.generate();
        OpenProgress progress = new OpenProgress(session);
        try {
            session.getAttributes().put(
                    CorrelationIdSupport.MDC_CORRELATION_ID,
                    correlationId);
            WebSocketSession safeSession =
                    new ConcurrentWebSocketSessionDecorator(
                            session,
                            SEND_TIME_LIMIT_MS,
                            SEND_BUFFER_SIZE_LIMIT);
            progress.context = registry.open(
                    session,
                    safeSession,
                    userId,
                    correlationId,
                    System.currentTimeMillis());
            progress.registryOpened = true;
            CorrelationIdSupport.runWithContext(
                    CorrelationIdSupport.context(
                            correlationId, "WS", path(session)),
                    () -> completeOpen(progress));
        } catch (RuntimeException exception) {
            CorrelationIdSupport.runWithContext(
                    CorrelationIdSupport.context(
                            correlationId, "WS", path(session)),
                    () -> rollbackOpen(progress));
            log.warn("Agent WebSocket connection setup failed sessionId={}: {}",
                    session.getId(), exception.getMessage());
        }
    }

    void pong(String connectionId) {
        heartbeat.recordPong(connectionId);
    }

    void close(WebSocketSession session, CloseStatus status) {
        AgentWebSocketConnectionRegistry.CloseSnapshot snapshot =
                registry.close(session.getId());
        if (snapshot == null) {
            return;
        }
        AgentWebSocketConnectionRegistry.ConnectionContext context =
                snapshot.context();
        CorrelationIdSupport.runWithContext(
                CorrelationIdSupport.context(
                        context.correlationId(), "WS", path(session)),
                () -> closeRegisteredConnection(snapshot));
    }

    private void closeRegisteredConnection(
            AgentWebSocketConnectionRegistry.CloseSnapshot snapshot) {
        AgentWebSocketConnectionRegistry.ConnectionContext context =
                snapshot.context();
        for (AgentWebSocketActiveTurn activeTurn : snapshot.activeTurns()) {
            activeTurn.failClientDelivery("CLIENT_DISCONNECTED");
        }
        for (java.util.concurrent.FutureTask<?> task : snapshot.tasks()) {
            task.cancel(true);
        }
        extensions.onConnectionClosed(context, snapshot.logicalSessionIds());
        rateLimiter.removeSession(context.connectionId());
        heartbeat.stop(context.connectionId());
        agentMetrics.wsDisconnected();
        long durationSeconds = Math.max(
                0L,
                (System.currentTimeMillis() - context.connectedAtMillis())
                        / 1_000L);
        log.info(
                "[{}] [AgentWS] Disconnected: userId={}, sessionId={}, duration={}s",
                context.correlationId(),
                context.userId(),
                context.connectionId(),
                durationSeconds);
    }

    private void completeOpen(OpenProgress progress) {
        AgentWebSocketConnectionRegistry.ConnectionContext context =
                progress.context;
        extensions.onConnectionOpened(context);
        progress.extensionsOpened = true;
        heartbeat.start(context.safeSession());
        progress.heartbeatStarted = true;
        agentMetrics.wsConnected();
        progress.metricConnected = true;
        log.info(
                "[{}] [AgentWS] Connected: userId={}, sessionId={}",
                context.correlationId(),
                context.userId(),
                context.connectionId());
    }

    private void rollbackOpen(OpenProgress progress) {
        AgentWebSocketConnectionRegistry.ConnectionContext context =
                progress.context;
        if (progress.metricConnected) {
            safely("metrics rollback", agentMetrics::wsDisconnected);
        }
        if (progress.heartbeatStarted && context != null) {
            safely("heartbeat rollback", () ->
                    heartbeat.stop(context.connectionId()));
        }
        if (progress.extensionsOpened && context != null) {
            safely("extension rollback", () ->
                    extensions.rollbackConnectionOpen(context));
        }
        if (progress.registryOpened && context != null) {
            safely("registry rollback", () ->
                    registry.close(context.connectionId()));
            safely("rate limiter rollback", () ->
                    rateLimiter.removeSession(context.connectionId()));
        }
        closeRawSession(
                progress.rawSession,
                CloseStatus.SERVER_ERROR.withReason(
                        "Agent connection setup failed"));
    }

    private void safely(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.warn("Agent WebSocket {} failed: {}",
                    operation, exception.getMessage());
        }
    }

    private void closeRawSession(
            WebSocketSession session,
            CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception exception) {
            log.warn("Agent WebSocket close failed sessionId={}: {}",
                    session.getId(), exception.getMessage());
        }
    }

    private Long authenticate(WebSocketSession session) {
        String ticket = extractQueryParam(session.getUri(), "ticket");
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        return ticketStore.consume(ticket);
    }

    private static String extractQueryParam(URI uri, String name) {
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        for (String part : uri.getQuery().split("&")) {
            int separator = part.indexOf('=');
            if (separator > 0
                    && name.equals(part.substring(0, separator))) {
                return URLDecoder.decode(
                        part.substring(separator + 1),
                        StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String path(WebSocketSession session) {
        return session.getUri() == null
                ? "/api/v1/agent/ws"
                : session.getUri().getPath();
    }

    private static final class OpenProgress {
        private final WebSocketSession rawSession;
        private AgentWebSocketConnectionRegistry.ConnectionContext context;
        private boolean registryOpened;
        private boolean extensionsOpened;
        private boolean heartbeatStarted;
        private boolean metricConnected;

        private OpenProgress(WebSocketSession rawSession) {
            this.rawSession = rawSession;
        }
    }
}
