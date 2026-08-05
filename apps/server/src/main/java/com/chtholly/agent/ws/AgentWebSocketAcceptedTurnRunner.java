package com.chtholly.agent.ws;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.ChthollyAgent;
import com.chtholly.agent.runtime.AgentTurnBudget;
import com.chtholly.common.tracing.CorrelationIdSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runs an admitted WebSocket turn through terminal delivery before releasing
 * distributed ownership.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentWebSocketAcceptedTurnRunner {

    private final ChthollyAgent agent;
    private final AgentTurnCoordinator turnCoordinator;
    private final AgentWebSocketConnectionRegistry registry;
    private final AgentWebSocketDeliveryService delivery;
    private final AgentWebSocketExtensionLifecycle extensions;

    /**
     * Creates the accepted-turn runner.
     *
     * @param agent Agent runtime
     * @param turnCoordinator distributed turn coordinator
     * @param registry connection resource registry
     * @param delivery transport delivery service
     * @param extensions optional extension lifecycle
     */
    public AgentWebSocketAcceptedTurnRunner(
            ChthollyAgent agent,
            AgentTurnCoordinator turnCoordinator,
            AgentWebSocketConnectionRegistry registry,
            AgentWebSocketDeliveryService delivery,
            AgentWebSocketExtensionLifecycle extensions) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.turnCoordinator = Objects.requireNonNull(
                turnCoordinator, "turnCoordinator");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    void run(
            AgentWebSocketConnectionRegistry.ConnectionContext connection,
            AgentWebSocketProtocolCodec.ChatRequest request,
            AgentWebSocketTurnAdmissionService.Admission admission) {
        AgentWebSocketActiveTurn activeTurn = admission.activeTurn();
        boolean accepted = false;
        boolean agentStarted = false;
        try {
            if (canContinue(connection, activeTurn)) {
                accepted = delivery.sendAccepted(
                        connection.safeSession(),
                        request.requestId(),
                        activeTurn);
            }
            if (accepted && canStartAgent(connection, activeTurn)) {
                agentStarted = true;
                runAgent(connection, request, admission, activeTurn);
            } else if (!activeTurn.isClientDeliveryResolved()) {
                activeTurn.failClientDelivery("CLIENT_DISCONNECTED");
            }
            finishClientDelivery(
                    connection, request, activeTurn, accepted);
            if (agentStarted) {
                extensions.afterTurn(connection.userId(), request.message());
            }
        } finally {
            registry.removeActiveTurn(connection.connectionId(), activeTurn);
            if (!activeTurn.releaseLeaseOnce(turnCoordinator)) {
                handleLeaseReleaseFailure(connection, activeTurn);
            }
        }
    }

    private boolean canContinue(
            AgentWebSocketConnectionRegistry.ConnectionContext connection,
            AgentWebSocketActiveTurn activeTurn) {
        return connection.safeSession().isOpen()
                && registry.isActiveTurn(
                        connection.connectionId(), activeTurn)
                && !activeTurn.control().isCancelled();
    }

    private boolean canStartAgent(
            AgentWebSocketConnectionRegistry.ConnectionContext connection,
            AgentWebSocketActiveTurn activeTurn) {
        return connection.safeSession().isOpen()
                && !activeTurn.control().isCancelled()
                && registry.tryStartAgentIfActive(
                        connection.connectionId(), activeTurn);
    }

    private void runAgent(
            AgentWebSocketConnectionRegistry.ConnectionContext connection,
            AgentWebSocketProtocolCodec.ChatRequest request,
            AgentWebSocketTurnAdmissionService.Admission admission,
            AgentWebSocketActiveTurn activeTurn) {
        Consumer<AgentEvent> eventSink = event -> acceptAgentEvent(
                connection, request, activeTurn, event);
        try {
            CorrelationIdSupport.runWithContext(
                    CorrelationIdSupport.context(
                            activeTurn.control().turnId(),
                            "WS",
                            path(connection.rawSession())),
                    () -> agent.run(
                            request.message(),
                            connection.userId(),
                            admission.memory(),
                            activeTurn.control(),
                            request.pageContext(),
                            request.taskType(),
                            eventSink));
        } catch (Exception exception) {
            log.warn("Agent turn failed turnId={}: {}",
                    activeTurn.control().turnId(), exception.getMessage());
            if (!activeTurn.control().isCancelled()) {
                activeTurn.sealTerminalIfAbsent(
                        delivery.turnFailedEvent());
            } else if (!activeTurn.isClientDeliveryResolved()) {
                activeTurn.failClientDelivery("CLIENT_DELIVERY_FAILED");
            }
        }
    }

    private void acceptAgentEvent(
            AgentWebSocketConnectionRegistry.ConnectionContext connection,
            AgentWebSocketProtocolCodec.ChatRequest request,
            AgentWebSocketActiveTurn activeTurn,
            AgentEvent event) {
        WebSocketSession session = connection.safeSession();
        if (!registry.isActiveTurn(
                connection.connectionId(), activeTurn)
                || !session.isOpen()) {
            activeTurn.failClientDelivery("CLIENT_DELIVERY_FAILED");
            throw cancelledDelivery();
        }
        try {
            activeTurn.acceptEvent(
                    event,
                    nonTerminal -> delivery.sendNonTerminal(
                            session,
                            nonTerminal,
                            request.requestId(),
                            activeTurn.control().turnId()));
        } catch (Exception exception) {
            log.warn("Agent event delivery failed turnId={}: {}",
                    activeTurn.control().turnId(), exception.getMessage());
            delivery.failIntermediateDelivery(activeTurn, session);
            throw cancelledDelivery();
        }
    }

    private void finishClientDelivery(
            AgentWebSocketConnectionRegistry.ConnectionContext connection,
            AgentWebSocketProtocolCodec.ChatRequest request,
            AgentWebSocketActiveTurn activeTurn,
            boolean accepted) {
        AgentEvent terminal = activeTurn.terminalEvent();
        if (terminal == null
                && accepted
                && !activeTurn.control().isCancelled()) {
            AgentEvent fallback = delivery.turnFailedEvent();
            activeTurn.sealTerminalIfAbsent(fallback);
            terminal = activeTurn.terminalEvent();
        }

        if (terminal != null && canContinue(connection, activeTurn)) {
            delivery.sendTerminal(
                    connection.safeSession(),
                    terminal,
                    request.requestId(),
                    activeTurn);
        } else if (!activeTurn.isClientDeliveryResolved()) {
            activeTurn.failClientDelivery("CLIENT_DELIVERY_FAILED");
        }
    }

    private void handleLeaseReleaseFailure(
            AgentWebSocketConnectionRegistry.ConnectionContext connection,
            AgentWebSocketActiveTurn activeTurn) {
        delivery.recordCoordinationReleaseFailure();
        if (!activeTurn.isClientDeliveryResolved()) {
            activeTurn.failClientDelivery(
                    "TURN_COORDINATION_UNAVAILABLE");
        }
        delivery.closeAfterDeliveryFailure(
                connection.safeSession(), activeTurn.control().turnId());
    }

    private static AgentTurnBudget.UnavailableException
            cancelledDelivery() {
        return AgentTurnBudget.unavailableForStage(
                AgentTurnBudget.UnavailableReason.CANCELLED,
                "client_delivery");
    }

    private static String path(WebSocketSession session) {
        return session.getUri() == null
                ? "/api/v1/agent/ws"
                : session.getUri().getPath();
    }
}
