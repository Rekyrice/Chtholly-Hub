package com.chtholly.agent.ws;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;

/**
 * Atomically owns connection-scoped WebSocket resources and accepted turns.
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentWebSocketConnectionRegistry {

    private final Map<String, ConnectionContext> connections =
            new ConcurrentHashMap<>();

    ConnectionContext open(
            WebSocketSession rawSession,
            WebSocketSession safeSession,
            long userId,
            String correlationId,
            long connectedAtMillis) {
        ConnectionContext created = new ConnectionContext(
                rawSession,
                safeSession,
                userId,
                correlationId,
                connectedAtMillis);
        ConnectionContext existing = connections.putIfAbsent(
                rawSession.getId(), created);
        if (existing != null) {
            throw new IllegalStateException(
                    "WebSocket connection is already registered");
        }
        return created;
    }

    Optional<ConnectionContext> findOpen(String connectionId) {
        ConnectionContext context = connections.get(connectionId);
        return context == null || !context.isOpen()
                ? Optional.empty()
                : Optional.of(context);
    }

    boolean trackTaskIfOpen(
            String connectionId,
            FutureTask<?> task) {
        ConnectionContext context = connections.get(connectionId);
        return context != null && context.trackTaskIfOpen(task);
    }

    void untrackTask(String connectionId, FutureTask<?> task) {
        ConnectionContext context = connections.get(connectionId);
        if (context != null) {
            context.untrackTask(task);
        }
    }

    int trackedTaskCount(String connectionId) {
        ConnectionContext context = connections.get(connectionId);
        return context == null ? 0 : context.trackedTaskCount();
    }

    boolean registerActiveTurnIfOpen(
            String connectionId,
            AgentWebSocketActiveTurn turn) {
        ConnectionContext context = connections.get(connectionId);
        return context != null && context.registerActiveTurnIfOpen(turn);
    }

    boolean isActiveTurn(
            String connectionId,
            AgentWebSocketActiveTurn expected) {
        ConnectionContext context = connections.get(connectionId);
        return context != null && context.isActiveTurn(expected);
    }

    boolean tryStartAgentIfActive(
            String connectionId,
            AgentWebSocketActiveTurn expected) {
        ConnectionContext context = connections.get(connectionId);
        return context != null && context.tryStartAgentIfActive(expected);
    }

    void removeActiveTurn(
            String connectionId,
            AgentWebSocketActiveTurn expected) {
        ConnectionContext context = connections.get(connectionId);
        if (context != null) {
            context.removeActiveTurn(expected);
        }
    }

    boolean rememberLogicalSessionIfOpen(
            String connectionId,
            String logicalSessionId) {
        ConnectionContext context = connections.get(connectionId);
        return context != null
                && context.rememberLogicalSessionIfOpen(logicalSessionId);
    }

    CloseSnapshot close(String connectionId) {
        ConnectionContext context = connections.remove(connectionId);
        return context == null ? null : context.closeAndSnapshot();
    }

    static final class ConnectionContext {

        private final WebSocketSession rawSession;
        private final WebSocketSession safeSession;
        private final long userId;
        private final String correlationId;
        private final long connectedAtMillis;
        private final Set<FutureTask<?>> tasks = new LinkedHashSet<>();
        private final Map<String, AgentWebSocketActiveTurn> activeTurns =
                new LinkedHashMap<>();
        private final Set<String> logicalSessionIds = new LinkedHashSet<>();
        private boolean open = true;

        private ConnectionContext(
                WebSocketSession rawSession,
                WebSocketSession safeSession,
                long userId,
                String correlationId,
                long connectedAtMillis) {
            this.rawSession = Objects.requireNonNull(rawSession, "rawSession");
            this.safeSession = Objects.requireNonNull(safeSession, "safeSession");
            this.userId = userId;
            this.correlationId = Objects.requireNonNull(
                    correlationId, "correlationId");
            this.connectedAtMillis = connectedAtMillis;
        }

        WebSocketSession rawSession() {
            return rawSession;
        }

        WebSocketSession safeSession() {
            return safeSession;
        }

        String connectionId() {
            return rawSession.getId();
        }

        long userId() {
            return userId;
        }

        String correlationId() {
            return correlationId;
        }

        long connectedAtMillis() {
            return connectedAtMillis;
        }

        synchronized boolean isOpen() {
            return open;
        }

        private synchronized boolean trackTaskIfOpen(FutureTask<?> task) {
            if (!open) {
                return false;
            }
            return tasks.add(Objects.requireNonNull(task, "task"));
        }

        private synchronized void untrackTask(FutureTask<?> task) {
            tasks.remove(task);
        }

        private synchronized int trackedTaskCount() {
            return tasks.size();
        }

        private synchronized boolean registerActiveTurnIfOpen(
                AgentWebSocketActiveTurn turn) {
            if (!open) {
                return false;
            }
            return activeTurns.putIfAbsent(
                    turn.control().turnId(), turn) == null;
        }

        private synchronized boolean isActiveTurn(
                AgentWebSocketActiveTurn expected) {
            return open
                    && !expected.control().isCancelled()
                    && activeTurns.get(expected.control().turnId()) == expected;
        }

        private synchronized boolean tryStartAgentIfActive(
                AgentWebSocketActiveTurn expected) {
            return isActiveTurn(expected);
        }

        private synchronized void removeActiveTurn(
                AgentWebSocketActiveTurn expected) {
            activeTurns.remove(expected.control().turnId(), expected);
        }

        private synchronized boolean rememberLogicalSessionIfOpen(
                String logicalSessionId) {
            if (!open) {
                return false;
            }
            logicalSessionIds.add(Objects.requireNonNull(
                    logicalSessionId, "logicalSessionId"));
            return true;
        }

        private synchronized CloseSnapshot closeAndSnapshot() {
            open = false;
            return new CloseSnapshot(
                    this,
                    List.copyOf(tasks),
                    List.copyOf(activeTurns.values()),
                    Set.copyOf(logicalSessionIds));
        }
    }

    record CloseSnapshot(
            ConnectionContext context,
            List<FutureTask<?>> tasks,
            List<AgentWebSocketActiveTurn> activeTurns,
            Set<String> logicalSessionIds) {

        CloseSnapshot {
            Objects.requireNonNull(context, "context");
            tasks = List.copyOf(new ArrayList<>(tasks));
            activeTurns = List.copyOf(new ArrayList<>(activeTurns));
            logicalSessionIds = Set.copyOf(logicalSessionIds);
        }
    }
}
