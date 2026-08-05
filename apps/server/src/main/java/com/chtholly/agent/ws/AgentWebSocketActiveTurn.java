package com.chtholly.agent.ws;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.runtime.AgentTurnControl;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Owns the transport-visible state of one accepted WebSocket turn.
 *
 * <p>The object is shared by the worker and the connection-close callback, so
 * lease-release ownership, terminal selection, and client-delivery resolution
 * are serialized here. Distributed release I/O runs outside this object's
 * monitor.</p>
 */
final class AgentWebSocketActiveTurn {

    private final long userId;
    private final String chatSessionId;
    private final AgentTurnControl control;
    private CompletableFuture<Boolean> leaseRelease;
    private boolean clientDeliveryResolved;
    private AgentEvent terminalEvent;

    AgentWebSocketActiveTurn(
            long userId,
            String chatSessionId,
            AgentTurnControl control) {
        this.userId = userId;
        this.chatSessionId = Objects.requireNonNull(
                chatSessionId, "chatSessionId");
        this.control = Objects.requireNonNull(control, "control");
    }

    long userId() {
        return userId;
    }

    String chatSessionId() {
        return chatSessionId;
    }

    AgentTurnControl control() {
        return control;
    }

    boolean releaseLeaseOnce(AgentTurnCoordinator coordinator) {
        Objects.requireNonNull(coordinator, "coordinator");
        CompletableFuture<Boolean> release;
        boolean owner = false;
        synchronized (this) {
            if (leaseRelease == null) {
                leaseRelease = new CompletableFuture<>();
                owner = true;
            }
            release = leaseRelease;
        }
        if (owner) {
            boolean released;
            try {
                released = coordinator.release(
                        userId,
                        chatSessionId,
                        control.turnId());
            } catch (RuntimeException exception) {
                released = false;
            } catch (Error error) {
                release.completeExceptionally(error);
                throw error;
            }
            release.complete(released);
        }
        return release.join();
    }

    void acceptEvent(
            AgentEvent event,
            EventSender sender) throws Exception {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(sender, "sender");
        synchronized (this) {
            if (terminalEvent != null) {
                return;
            }
            if (isTerminal(event)) {
                terminalEvent = event;
                return;
            }
        }
        sender.send(event);
    }

    synchronized boolean sealTerminalIfAbsent(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        if (terminalEvent != null) {
            return false;
        }
        terminalEvent = event;
        return true;
    }

    synchronized AgentEvent terminalEvent() {
        return terminalEvent;
    }

    void completeClientDelivery(
            boolean delivered,
            String terminalType,
            String code) {
        synchronized (this) {
            if (clientDeliveryResolved) {
                return;
            }
            clientDeliveryResolved = true;
        }
        control.completeClientDelivery(delivered, terminalType, code);
    }

    void failClientDelivery(String code) {
        failClientDelivery("", code);
    }

    void failClientDelivery(String terminalType, String code) {
        synchronized (this) {
            if (clientDeliveryResolved) {
                return;
            }
            clientDeliveryResolved = true;
        }
        control.cancel();
        control.completeClientDelivery(false, terminalType, code);
    }

    synchronized boolean isClientDeliveryResolved() {
        return clientDeliveryResolved;
    }

    private static boolean isTerminal(AgentEvent event) {
        return "final".equals(event.type()) || "error".equals(event.type());
    }

    @FunctionalInterface
    interface EventSender {
        void send(AgentEvent event) throws Exception;
    }
}
