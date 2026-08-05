package com.chtholly.agent.ws;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Objects;

/** Adapts Spring WebSocket callbacks to Agent application services. */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private final AgentWebSocketConnectionLifecycle connectionLifecycle;
    private final AgentWebSocketProtocolDispatcher protocolDispatcher;

    /**
     * Creates the production Spring WebSocket adapter.
     *
     * @param connectionLifecycle connection callback service
     * @param protocolDispatcher inbound message dispatcher
     */
    public AgentWebSocketHandler(
            AgentWebSocketConnectionLifecycle connectionLifecycle,
            AgentWebSocketProtocolDispatcher protocolDispatcher) {
        this.connectionLifecycle = Objects.requireNonNull(
                connectionLifecycle, "connectionLifecycle");
        this.protocolDispatcher = Objects.requireNonNull(
                protocolDispatcher, "protocolDispatcher");
    }

    /** Delegates an authenticated connection-open callback. */
    @Override
    public void afterConnectionEstablished(WebSocketSession session)
            throws Exception {
        connectionLifecycle.open(session);
    }

    /** Delegates an inbound text frame. */
    @Override
    protected void handleTextMessage(
            WebSocketSession session,
            TextMessage message) {
        protocolDispatcher.dispatch(session, message.getPayload());
    }

    /** Delegates an inbound heartbeat response. */
    @Override
    protected void handlePongMessage(
            WebSocketSession session,
            PongMessage message) {
        connectionLifecycle.pong(session.getId());
    }

    /** Delegates a connection-close callback. */
    @Override
    public void afterConnectionClosed(
            WebSocketSession session,
            CloseStatus status) {
        connectionLifecycle.close(session, status);
    }
}
