package com.chtholly.agent.ws;

import com.chtholly.agent.cognitive.CognitiveEngine;
import com.chtholly.agent.learning.InsightService;
import com.chtholly.agent.memory.AgentMemoryStore;
import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.notification.NotificationService;
import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.common.tracing.CorrelationIdSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runs optional notification, character-state, reflection, and cognitive work.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentWebSocketExtensionLifecycle {

    private final CharacterStateService characterStateService;
    private final AgentMemoryStore memoryStore;
    private final ObjectProvider<InsightService> insightServiceProvider;
    private final ObjectProvider<CognitiveEngine> cognitiveEngineProvider;
    private final ObjectProvider<NotificationService> notificationProvider;
    private final AgentWebSocketDeliveryService delivery;
    private final AgentWebSocketTaskExecutor taskExecutor;

    /**
     * Creates the optional extension boundary.
     *
     * @param characterStateService character state service
     * @param memoryStore conversation memory store
     * @param insightServiceProvider optional insight service provider
     * @param cognitiveEngineProvider optional cognitive engine provider
     * @param notificationProvider optional notification service provider
     * @param delivery WebSocket delivery boundary
     * @param taskExecutor context-preserving background executor
     */
    public AgentWebSocketExtensionLifecycle(
            CharacterStateService characterStateService,
            AgentMemoryStore memoryStore,
            ObjectProvider<InsightService> insightServiceProvider,
            ObjectProvider<CognitiveEngine> cognitiveEngineProvider,
            ObjectProvider<NotificationService> notificationProvider,
            AgentWebSocketDeliveryService delivery,
            AgentWebSocketTaskExecutor taskExecutor) {
        this.characterStateService = Objects.requireNonNull(
                characterStateService, "characterStateService");
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
        this.insightServiceProvider = Objects.requireNonNull(
                insightServiceProvider, "insightServiceProvider");
        this.cognitiveEngineProvider = Objects.requireNonNull(
                cognitiveEngineProvider, "cognitiveEngineProvider");
        this.notificationProvider = Objects.requireNonNull(
                notificationProvider, "notificationProvider");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.taskExecutor = Objects.requireNonNull(
                taskExecutor, "taskExecutor");
    }

    void onConnectionOpened(
            AgentWebSocketConnectionRegistry.ConnectionContext context) {
        NotificationService notifications;
        try {
            notifications = notificationProvider.getIfAvailable();
        } catch (RuntimeException exception) {
            log.warn("Agent notification extension lookup failed: {}",
                    exception.getMessage());
            return;
        }
        if (notifications == null) {
            return;
        }
        try {
            notifications.registerSession(
                    context.userId(),
                    context.connectionId(),
                    notification -> deliverNotification(context, notification));
        } catch (RuntimeException exception) {
            log.warn("Agent notification registration failed userId={}: {}",
                    context.userId(), exception.getMessage());
        }
        try {
            for (Notification notification
                    : notifications.getPendingNotifications(context.userId())) {
                deliverNotification(context, notification);
            }
        } catch (RuntimeException exception) {
            log.warn("Agent notification replay failed userId={}: {}",
                    context.userId(), exception.getMessage());
        }
    }

    void onConnectionClosed(
            AgentWebSocketConnectionRegistry.ConnectionContext context,
            Set<String> logicalSessionIds) {
        unregisterNotifications(context);
        for (String logicalSessionId : logicalSessionIds) {
            schedule(() -> reflectConversation(
                    context.userId(), logicalSessionId),
                    "conversation reflection");
        }
        schedule(this::triggerCognitiveCycle, "cognitive cycle");
    }

    void rollbackConnectionOpen(
            AgentWebSocketConnectionRegistry.ConnectionContext context) {
        unregisterNotifications(context);
    }

    void afterTurn(long userId, String text) {
        Instant occurredAt = Instant.now();
        schedule(() -> {
            characterStateService.updateEmotion(userId, text, occurredAt);
            characterStateService.recordInteraction(userId);
        }, "character state update");
    }

    private void deliverNotification(
            AgentWebSocketConnectionRegistry.ConnectionContext context,
            Notification notification) {
        CorrelationIdSupport.runWithContext(
                CorrelationIdSupport.context(
                        context.correlationId(),
                        "WS",
                        path(context.rawSession())),
                () -> sendNotification(context.safeSession(), notification));
    }

    private void sendNotification(
            WebSocketSession session,
            Notification notification) {
        if (session.isOpen()) {
            delivery.sendProactiveOrClose(session, notification);
        }
    }

    private void unregisterNotifications(
            AgentWebSocketConnectionRegistry.ConnectionContext context) {
        try {
            NotificationService notifications =
                    notificationProvider.getIfAvailable();
            if (notifications != null) {
                notifications.unregisterSession(
                        context.userId(), context.connectionId());
            }
        } catch (RuntimeException exception) {
            log.warn("Agent notification unregistration failed userId={}: {}",
                    context.userId(), exception.getMessage());
        }
    }

    private void reflectConversation(
            long userId,
            String logicalSessionId) {
        if (!AgentChatSessionSupport.isValid(logicalSessionId)) {
            return;
        }
        try {
            InsightService insightService =
                    insightServiceProvider.getIfAvailable();
            if (insightService == null) {
                return;
            }
            List<AgentTurn> conversation = memoryStore.getTurns(
                    userId, logicalSessionId);
            insightService.reflectOnConversation(userId, conversation);
        } catch (Exception exception) {
            log.warn(
                    "Agent insight reflection failed userId={}, sessionId={}: {}",
                    userId,
                    logicalSessionId,
                    exception.getMessage());
        }
    }

    private void triggerCognitiveCycle() {
        try {
            CognitiveEngine cognitiveEngine =
                    cognitiveEngineProvider.getIfAvailable();
            if (cognitiveEngine != null) {
                cognitiveEngine.triggerIfDue();
            }
        } catch (Exception exception) {
            log.warn("Agent cognitive cycle failed: {}",
                    exception.getMessage());
        }
    }

    private void schedule(Runnable action, String operation) {
        try {
            taskExecutor.execute(() -> {
                try {
                    action.run();
                } catch (RuntimeException exception) {
                    log.warn("Agent {} failed: {}",
                            operation, exception.getMessage());
                }
            });
        } catch (RuntimeException exception) {
            log.warn("Agent {} scheduling failed: {}",
                    operation, exception.getMessage());
        }
    }

    private static String path(WebSocketSession session) {
        return session.getUri() == null
                ? "/api/v1/agent/ws"
                : session.getUri().getPath();
    }
}
