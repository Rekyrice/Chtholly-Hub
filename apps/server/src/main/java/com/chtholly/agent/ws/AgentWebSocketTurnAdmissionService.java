package com.chtholly.agent.ws;

import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentMemoryStore;
import com.chtholly.agent.runtime.AgentTurnControl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Captures memory and claims ownership before a WebSocket turn is accepted.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentWebSocketTurnAdmissionService {

    private static final int TURN_LEASE_GRACE_SECONDS = 15;

    private final AgentMemoryStore memoryStore;
    private final AgentTurnCoordinator turnCoordinator;
    private final AgentProperties properties;
    private final AgentWebSocketConnectionRegistry registry;
    private final AgentWebSocketDeliveryService delivery;

    /**
     * Creates the turn admission service.
     *
     * @param memoryStore conversation memory store
     * @param turnCoordinator distributed turn coordinator
     * @param properties Agent runtime properties
     * @param registry connection resource registry
     * @param delivery transport delivery service
     */
    public AgentWebSocketTurnAdmissionService(
            AgentMemoryStore memoryStore,
            AgentTurnCoordinator turnCoordinator,
            AgentProperties properties,
            AgentWebSocketConnectionRegistry registry,
            AgentWebSocketDeliveryService delivery) {
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
        this.turnCoordinator = Objects.requireNonNull(
                turnCoordinator, "turnCoordinator");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    Optional<Admission> admit(
            AgentWebSocketConnectionRegistry.ConnectionContext connection,
            AgentWebSocketProtocolCodec.ChatRequest request) {
        AgentConversationMemory memory = loadMemory(connection, request);
        if (memory == null) {
            return Optional.empty();
        }
        Duration timeout = Duration.ofSeconds(Math.max(
                1, properties.getTurnTimeoutSeconds()));
        String proposedTurnId = UUID.randomUUID().toString();
        AgentTurnCoordinator.AcquireResult acquisition;
        try {
            acquisition = turnCoordinator.acquire(
                    connection.userId(),
                    request.sessionId(),
                    request.requestId(),
                    proposedTurnId,
                    timeout.plusSeconds(TURN_LEASE_GRACE_SECONDS));
        } catch (RuntimeException exception) {
            acquisition = new AgentTurnCoordinator.AcquireResult(
                    AgentTurnCoordinator.AcquireStatus.UNAVAILABLE,
                    proposedTurnId);
        }
        if (acquisition.status()
                != AgentTurnCoordinator.AcquireStatus.ACQUIRED) {
            rejectAcquisition(connection, request, acquisition.status());
            return Optional.empty();
        }

        String turnId = acquisition.turnId().isBlank()
                ? proposedTurnId
                : acquisition.turnId();
        AgentWebSocketActiveTurn activeTurn =
                new AgentWebSocketActiveTurn(
                        connection.userId(),
                        request.sessionId(),
                        AgentTurnControl.create(
                                request.requestId(),
                                turnId,
                                request.sessionId(),
                                connection.connectionId(),
                                timeout));
        if (!registry.registerActiveTurnIfOpen(
                connection.connectionId(), activeTurn)) {
            activeTurn.failClientDelivery("CLIENT_DISCONNECTED");
            if (!activeTurn.releaseLeaseOnce(turnCoordinator)) {
                delivery.recordCoordinationReleaseFailure();
            }
            return Optional.empty();
        }
        return Optional.of(new Admission(memory, activeTurn));
    }

    private AgentConversationMemory loadMemory(
            AgentWebSocketConnectionRegistry.ConnectionContext connection,
            AgentWebSocketProtocolCodec.ChatRequest request) {
        try {
            return memoryStore.getOrCreateMemory(
                    connection.userId(), request.sessionId());
        } catch (Exception exception) {
            log.warn(
                    "Agent memory snapshot unavailable userId={}, sessionId={}: {}",
                    connection.userId(),
                    request.sessionId(),
                    exception.getMessage());
            delivery.rejectOrClose(
                    connection.safeSession(),
                    request.requestId(),
                    "MEMORY_UNAVAILABLE");
            return null;
        }
    }

    private void rejectAcquisition(
            AgentWebSocketConnectionRegistry.ConnectionContext connection,
            AgentWebSocketProtocolCodec.ChatRequest request,
            AgentTurnCoordinator.AcquireStatus status) {
        String code = switch (status) {
            case TURN_IN_PROGRESS -> "TURN_IN_PROGRESS";
            case DUPLICATE_REQUEST -> "DUPLICATE_REQUEST";
            case UNAVAILABLE -> "TURN_COORDINATION_UNAVAILABLE";
            case ACQUIRED -> throw new IllegalStateException(
                    "unreachable acquire status");
        };
        delivery.rejectOrClose(
                connection.safeSession(), request.requestId(), code);
    }

    record Admission(
            AgentConversationMemory memory,
            AgentWebSocketActiveTurn activeTurn) {
    }
}
