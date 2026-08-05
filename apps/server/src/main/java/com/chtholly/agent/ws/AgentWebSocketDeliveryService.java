package com.chtholly.agent.ws;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.observability.AgentMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.Objects;

/**
 * Delivers Agent WebSocket protocol events and contains transport failures.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentWebSocketDeliveryService {

    private final AgentWebSocketProtocolCodec protocol;
    private final AgentMetrics agentMetrics;

    /**
     * Creates the transport delivery service.
     *
     * @param protocol WebSocket protocol codec
     * @param agentMetrics Agent metrics recorder
     */
    public AgentWebSocketDeliveryService(
            AgentWebSocketProtocolCodec protocol,
            AgentMetrics agentMetrics) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.agentMetrics = Objects.requireNonNull(
                agentMetrics, "agentMetrics");
    }

    boolean rejectOrClose(
            WebSocketSession session,
            String requestId,
            String code) {
        try {
            protocol.sendRejected(session, requestId, code);
            return true;
        } catch (Exception exception) {
            log.warn("Agent request rejection delivery failed code={}: {}",
                    code, exception.getMessage());
            closeAfterDeliveryFailure(session, requestId);
            return false;
        }
    }

    boolean rejectOrClose(
            WebSocketSession session,
            String requestId,
            String code,
            String message) {
        try {
            protocol.sendRejected(session, requestId, code, message);
            return true;
        } catch (Exception exception) {
            log.warn("Agent request rejection delivery failed code={}: {}",
                    code, exception.getMessage());
            closeAfterDeliveryFailure(session, requestId);
            return false;
        }
    }

    void genericErrorOrClose(WebSocketSession session) {
        try {
            protocol.sendGenericError(session);
        } catch (Exception exception) {
            log.warn("Agent generic error delivery failed: {}",
                    exception.getMessage());
            closeAfterDeliveryFailure(session, "request");
        }
    }

    boolean sendAccepted(
            WebSocketSession session,
            String requestId,
            AgentWebSocketActiveTurn activeTurn) {
        try {
            protocol.sendAccepted(
                    session, requestId, activeTurn.control().turnId());
            return true;
        } catch (Exception exception) {
            log.warn("Agent accepted event delivery failed turnId={}: {}",
                    activeTurn.control().turnId(), exception.getMessage());
            failClientDelivery(activeTurn, session);
            return false;
        }
    }

    void sendNonTerminal(
            WebSocketSession session,
            AgentEvent event,
            String requestId,
            String turnId) throws Exception {
        protocol.sendEvent(session, event, requestId, turnId);
    }

    void sendProactiveOrClose(
            WebSocketSession session,
            Notification notification) {
        try {
            protocol.sendProactive(session, notification);
        } catch (Exception exception) {
            agentMetrics.recordError("proactive_delivery");
            log.warn("Agent proactive notification delivery failed: {}",
                    exception.getMessage());
            closeAfterDeliveryFailure(session, notification.type());
        }
    }

    void failIntermediateDelivery(
            AgentWebSocketActiveTurn activeTurn,
            WebSocketSession session) {
        failClientDelivery(activeTurn, session);
    }

    void sendTerminal(
            WebSocketSession session,
            AgentEvent terminal,
            String requestId,
            AgentWebSocketActiveTurn activeTurn) {
        try {
            protocol.sendEvent(
                    session,
                    terminal,
                    requestId,
                    activeTurn.control().turnId());
            activeTurn.completeClientDelivery(
                    true,
                    terminal.type(),
                    protocol.terminalCode(terminal));
        } catch (Exception exception) {
            activeTurn.failClientDelivery(
                    terminal.type(), "CLIENT_DELIVERY_FAILED");
            agentMetrics.recordError("client_delivery");
            log.warn("Agent terminal delivery failed turnId={}: {}",
                    activeTurn.control().turnId(), exception.getMessage());
            closeAfterDeliveryFailure(
                    session, activeTurn.control().turnId());
        }
    }

    void recordCoordinationReleaseFailure() {
        agentMetrics.recordError("turn_coordination_release");
    }

    AgentEvent turnFailedEvent() {
        return protocol.turnFailedEvent();
    }

    void closeAfterDeliveryFailure(
            WebSocketSession session,
            String operationId) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR.withReason(
                        "Agent turn delivery failed"));
            }
        } catch (Exception exception) {
            log.warn("Agent WebSocket close failed operationId={}: {}",
                    operationId, exception.getMessage());
        }
    }

    private void failClientDelivery(
            AgentWebSocketActiveTurn activeTurn,
            WebSocketSession session) {
        activeTurn.failClientDelivery("CLIENT_DELIVERY_FAILED");
        agentMetrics.recordError("client_delivery");
        closeAfterDeliveryFailure(session, activeTurn.control().turnId());
    }
}
