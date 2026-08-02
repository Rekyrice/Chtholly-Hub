package com.chtholly.agent.runtime;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Immutable turn identity plus cooperative cancellation and deadline state.
 */
@Slf4j
public final class AgentTurnControl {

    private final String requestId;
    private final String turnId;
    private final String chatSessionId;
    private final String connectionId;
    private final AtomicBoolean cancelled;
    private final AgentTurnBudget budget;
    private final CountDownLatch clientDeliveryResolved;
    private volatile ClientDeliveryStatus clientDeliveryStatus;
    private volatile String clientTerminalType = "";
    private volatile String clientDeliveryCode = "";
    private Runnable clientDeliveryCompletion;

    private AgentTurnControl(
            String requestId,
            String turnId,
            String chatSessionId,
            String connectionId,
            Duration timeout) {
        this.requestId = requireText(requestId, "requestId");
        this.turnId = requireText(turnId, "turnId");
        this.chatSessionId = requireText(chatSessionId, "chatSessionId");
        this.connectionId = requireText(connectionId, "connectionId");
        this.cancelled = new AtomicBoolean();
        boolean direct = "direct".equals(this.connectionId);
        this.clientDeliveryStatus = direct
                ? ClientDeliveryStatus.NOT_APPLICABLE
                : ClientDeliveryStatus.PENDING;
        this.clientDeliveryResolved = new CountDownLatch(direct ? 0 : 1);
        this.budget = AgentTurnBudget.start(
                Objects.requireNonNull(timeout, "timeout"),
                cancelled::get);
    }

    /**
     * Creates a turn control accepted by the WebSocket boundary.
     *
     * @param requestId client request identifier
     * @param turnId server turn identifier
     * @param chatSessionId logical conversation identifier
     * @param connectionId transport connection identifier
     * @param timeout global whole-turn timeout
     * @return new turn control
     */
    public static AgentTurnControl create(
            String requestId,
            String turnId,
            String chatSessionId,
            String connectionId,
            Duration timeout) {
        return new AgentTurnControl(requestId, turnId, chatSessionId, connectionId, timeout);
    }

    /** Creates a generated identity for non-WebSocket callers and legacy tests. */
    public static AgentTurnControl standalone(String chatSessionId, Duration timeout) {
        String id = UUID.randomUUID().toString();
        String safeSession = chatSessionId == null || chatSessionId.isBlank()
                ? "direct"
                : chatSessionId;
        return create(id, UUID.randomUUID().toString(), safeSession, "direct", timeout);
    }

    /** Marks this turn cancelled and returns whether this call changed its state. */
    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public String requestId() {
        return requestId;
    }

    public String turnId() {
        return turnId;
    }

    public String chatSessionId() {
        return chatSessionId;
    }

    public String connectionId() {
        return connectionId;
    }

    public AgentTurnBudget budget() {
        return budget;
    }

    /** Records the single terminal delivery outcome observed by the WebSocket boundary. */
    public void completeClientDelivery(
            boolean delivered,
            String terminalType,
            String code) {
        Runnable completion;
        synchronized (this) {
            if (clientDeliveryStatus != ClientDeliveryStatus.PENDING) {
                return;
            }
            clientTerminalType = normalize(terminalType);
            clientDeliveryCode = normalize(code);
            clientDeliveryStatus = delivered
                    ? ClientDeliveryStatus.DELIVERED
                    : ClientDeliveryStatus.FAILED;
            completion = clientDeliveryCompletion;
            clientDeliveryCompletion = null;
            clientDeliveryResolved.countDown();
        }
        runCompletion(completion);
    }

    /** Runs a callback once transport delivery is resolved, without blocking the agent worker. */
    public void onClientDeliveryResolved(Runnable completion) {
        Objects.requireNonNull(completion, "completion");
        synchronized (this) {
            if (clientDeliveryStatus == ClientDeliveryStatus.PENDING) {
                Runnable previous = clientDeliveryCompletion;
                clientDeliveryCompletion = previous == null
                        ? completion
                        : () -> {
                            try {
                                previous.run();
                            } finally {
                                completion.run();
                            }
                        };
                return;
            }
        }
        runCompletion(completion);
    }

    /** Waits for the transport boundary to resolve terminal delivery without exceeding the supplied bound. */
    public boolean awaitClientDelivery(Duration timeout) {
        if (clientDeliveryStatus != ClientDeliveryStatus.PENDING) {
            return true;
        }
        Duration safeTimeout = timeout == null || timeout.isNegative() ? Duration.ZERO : timeout;
        try {
            return clientDeliveryResolved.await(safeTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public ClientDeliveryStatus clientDeliveryStatus() {
        return clientDeliveryStatus;
    }

    public String clientTerminalType() {
        return clientTerminalType;
    }

    public String clientDeliveryCode() {
        return clientDeliveryCode;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private void runCompletion(Runnable completion) {
        if (completion == null) {
            return;
        }
        try {
            completion.run();
        } catch (RuntimeException exception) {
            log.warn("Agent turn delivery completion failed turnId={}: {}", turnId, exception.getMessage(), exception);
        }
    }

    public enum ClientDeliveryStatus {
        PENDING,
        DELIVERED,
        FAILED,
        NOT_APPLICABLE
    }
}
