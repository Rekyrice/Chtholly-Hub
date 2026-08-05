package com.chtholly.agent.ws;

import com.chtholly.common.tracing.CorrelationIdSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Objects;
import java.util.concurrent.FutureTask;

/**
 * Validates inbound Agent protocol messages and dispatches accepted chat work.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentWebSocketProtocolDispatcher {

    private final AgentWebSocketProtocolCodec protocol;
    private final AgentSessionRateLimiter rateLimiter;
    private final AgentWebSocketConnectionRegistry registry;
    private final AgentWebSocketTurnSubmissionService turnSubmission;
    private final AgentWebSocketTaskExecutor taskExecutor;

    /**
     * Creates an inbound protocol dispatcher.
     *
     * @param protocol WebSocket protocol codec
     * @param rateLimiter per-connection rate limiter
     * @param registry connection resource registry
     * @param turnSubmission accepted-turn transaction service
     * @param taskExecutor context-preserving task executor
     */
    public AgentWebSocketProtocolDispatcher(
            AgentWebSocketProtocolCodec protocol,
            AgentSessionRateLimiter rateLimiter,
            AgentWebSocketConnectionRegistry registry,
            AgentWebSocketTurnSubmissionService turnSubmission,
            AgentWebSocketTaskExecutor taskExecutor) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.turnSubmission = Objects.requireNonNull(
                turnSubmission, "turnSubmission");
        this.taskExecutor = Objects.requireNonNull(
                taskExecutor, "taskExecutor");
    }

    void dispatch(WebSocketSession session, String payload) {
        String connectionId = session.getId();
        FutureTask<Void> task = new FutureTask<>(
                () -> process(connectionId, payload), null) {
            @Override
            protected void done() {
                registry.untrackTask(connectionId, this);
            }
        };
        if (!registry.trackTaskIfOpen(connectionId, task)) {
            return;
        }
        try {
            taskExecutor.execute(task);
        } catch (RuntimeException exception) {
            task.cancel(false);
            registry.untrackTask(connectionId, task);
            log.warn("Agent WebSocket task submission failed sessionId={}: {}",
                    connectionId, exception.getMessage());
            rejectExecutorUnavailable(connectionId, payload);
        }
    }

    private void process(String connectionId, String payload) {
        AgentWebSocketConnectionRegistry.ConnectionContext connection =
                registry.findOpen(connectionId).orElse(null);
        if (connection == null) {
            return;
        }
        String correlationId = CorrelationIdSupport.generate();
        CorrelationIdSupport.runWithContext(
                CorrelationIdSupport.context(
                        correlationId,
                        "WS",
                        path(connection.rawSession())),
                () -> processWithContext(connection, payload));
    }

    private void processWithContext(
            AgentWebSocketConnectionRegistry.ConnectionContext connection,
            String payload) {
        try {
            AgentWebSocketProtocolCodec.InboundEnvelope envelope =
                    protocol.decode(payload);
            if (!"chat".equals(envelope.type())) {
                unknownTypeOrClose(connection.safeSession());
                return;
            }
            if (!protocol.isValidRequestId(envelope.requestId())) {
                rejectOrClose(
                        connection.safeSession(),
                        envelope.requestId(),
                        "INVALID_REQUEST_ID",
                        "requestId 格式无效");
                return;
            }
            if (!rateLimiter.tryAcquireChat(connection.connectionId())) {
                rejectOrClose(
                        connection.safeSession(),
                        envelope.requestId(),
                        "RATE_LIMITED",
                        "发送过于频繁，请稍后重试");
                return;
            }

            AgentWebSocketProtocolCodec.ChatRequest request =
                    protocol.chatRequest(envelope);
            if (request.message().isEmpty()) {
                rejectOrClose(
                        connection.safeSession(),
                        request.requestId(),
                        "EMPTY_MESSAGE",
                        "消息不能为空");
                return;
            }
            if (!AgentChatSessionSupport.isValid(request.sessionId())) {
                rejectOrClose(
                        connection.safeSession(),
                        request.requestId(),
                        "INVALID_SESSION_ID",
                        "缺少或无效的 sessionId");
                return;
            }
            if (!registry.rememberLogicalSessionIfOpen(
                    connection.connectionId(), request.sessionId())) {
                return;
            }
            turnSubmission.submit(connection, request);
        } catch (Exception exception) {
            log.warn("Agent WebSocket payload handling failed: {}",
                    exception.getMessage());
            registry.findOpen(connection.connectionId()).ifPresent(open -> {
                genericErrorOrClose(open.safeSession());
            });
        }
    }

    private void rejectExecutorUnavailable(
            String connectionId,
            String payload) {
        AgentWebSocketConnectionRegistry.ConnectionContext connection =
                registry.findOpen(connectionId).orElse(null);
        if (connection == null) {
            return;
        }
        try {
            String requestId = protocol.decode(payload).requestId();
            rejectOrClose(
                    connection.safeSession(),
                    requestId,
                    "EXECUTOR_UNAVAILABLE",
                    "服务暂时繁忙，请稍后重试");
        } catch (Exception exception) {
            log.warn("Agent executor rejection protocol failed: {}",
                    exception.getMessage());
            closeAfterProtocolFailure(connection.safeSession());
        }
    }

    private void rejectOrClose(
            WebSocketSession session,
            String requestId,
            String code,
            String message) {
        try {
            protocol.sendRejected(session, requestId, code, message);
        } catch (Exception exception) {
            log.warn("Agent rejection delivery failed code={}: {}",
                    code, exception.getMessage());
            closeAfterProtocolFailure(session);
        }
    }

    private void unknownTypeOrClose(WebSocketSession session) {
        try {
            protocol.sendUnknownType(session);
        } catch (Exception exception) {
            log.warn("Agent unknown-type delivery failed: {}",
                    exception.getMessage());
            closeAfterProtocolFailure(session);
        }
    }

    private void genericErrorOrClose(WebSocketSession session) {
        try {
            protocol.sendGenericError(session);
        } catch (Exception exception) {
            log.warn("Agent WebSocket error delivery failed: {}",
                    exception.getMessage());
            closeAfterProtocolFailure(session);
        }
    }

    private void closeAfterProtocolFailure(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(org.springframework.web.socket.CloseStatus
                        .SERVER_ERROR.withReason("Agent protocol delivery failed"));
            }
        } catch (Exception closeException) {
            log.warn("Agent WebSocket protocol close failed: {}",
                    closeException.getMessage());
        }
    }

    private static String path(WebSocketSession session) {
        return session.getUri() == null
                ? "/api/v1/agent/ws"
                : session.getUri().getPath();
    }
}
