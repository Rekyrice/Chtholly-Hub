package com.chtholly.agent.ws;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.ChthollyAgent;
import com.chtholly.agent.cognitive.CognitiveEngine;
import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.learning.InsightService;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentMemoryStore;
import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.notification.NotificationService;
import com.chtholly.agent.observability.AgentMetrics;
import com.chtholly.agent.runtime.AgentTurnBudget;
import com.chtholly.agent.runtime.AgentTurnControl;
import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.common.tracing.CorrelationIdSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Agent WebSocket：客户端发送 chat，服务端推送 ReAct 事件流。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentWebSocketHandler extends TextWebSocketHandler {

    /** 与 {@link com.chtholly.agent.config.AgentWebSocketConfig} 中 sendTimeLimit 一致。 */
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;
    private static final int TURN_LEASE_GRACE_SECONDS = 15;
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final ChthollyAgent agent;
    private final ObjectMapper objectMapper;
    private final AgentMemoryStore memoryStore;
    private final AgentWsTicketStore ticketStore;
    private final AgentSessionRateLimiter rateLimiter;
    private final AgentWebSocketHeartbeat heartbeat;
    private final AgentMetrics agentMetrics;
    private final CharacterStateService characterStateService;
    private final ObjectProvider<InsightService> insightServiceProvider;
    private final ObjectProvider<CognitiveEngine> cognitiveEngineProvider;
    private final ObjectProvider<NotificationService> proactiveNotificationServiceProvider;
    private final AgentTurnCoordinator turnCoordinator;
    private final AgentProperties properties;
    private final Executor executor;

    /** 原始 sessionId -> 线程安全装饰 session（并发 send 串行化）。 */
    private final Map<String, WebSocketSession> safeSessions = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionUsers = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionConnectedAt = new ConcurrentHashMap<>();
    private final Map<String, String> sessionChatSessionIds = new ConcurrentHashMap<>();
    private final Map<String, Set<FutureTask<Void>>> connectionTasks = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ActiveTurn>> connectionTurns = new ConcurrentHashMap<>();

    /**
     * Creates the production WebSocket handler with a virtual-thread executor.
     *
     * @param agent ReAct agent runtime.
     * @param objectMapper JSON serializer.
     * @param memoryStore Conversation memory store.
     * @param ticketStore One-time WebSocket ticket store.
     * @param rateLimiter Per-session rate limiter.
     * @param heartbeat WebSocket heartbeat coordinator.
     * @param agentMetrics Agent metrics recorder.
     * @param characterStateService Character state service.
     * @param insightServiceProvider Optional conversation reflection service provider.
     * @param cognitiveEngineProvider Optional cognitive engine provider.
     * @param proactiveNotificationServiceProvider Optional proactive notification service provider.
     * @param turnCoordinator cross-instance turn ownership coordinator
     * @param properties agent runtime properties
     */
    @Autowired
    public AgentWebSocketHandler(ChthollyAgent agent,
                                 ObjectMapper objectMapper,
                                 AgentMemoryStore memoryStore,
                                 AgentWsTicketStore ticketStore,
                                 AgentSessionRateLimiter rateLimiter,
                                 AgentWebSocketHeartbeat heartbeat,
                                 AgentMetrics agentMetrics,
                                 CharacterStateService characterStateService,
                                 ObjectProvider<InsightService> insightServiceProvider,
                                 ObjectProvider<CognitiveEngine> cognitiveEngineProvider,
                                 ObjectProvider<NotificationService> proactiveNotificationServiceProvider,
                                 AgentTurnCoordinator turnCoordinator,
                                 AgentProperties properties) {
        // 生产环境继续使用虚拟线程，避免长耗时 Agent 调用阻塞 WebSocket 处理线程。
        this(agent, objectMapper, memoryStore, ticketStore, rateLimiter, heartbeat, agentMetrics,
                characterStateService, insightServiceProvider, cognitiveEngineProvider,
                proactiveNotificationServiceProvider,
                turnCoordinator, properties,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    AgentWebSocketHandler(ChthollyAgent agent,
                          ObjectMapper objectMapper,
                          AgentMemoryStore memoryStore,
                          AgentWsTicketStore ticketStore,
                          AgentSessionRateLimiter rateLimiter,
                          AgentWebSocketHeartbeat heartbeat,
                          AgentMetrics agentMetrics,
                          CharacterStateService characterStateService,
                          ObjectProvider<InsightService> insightServiceProvider,
                          ObjectProvider<CognitiveEngine> cognitiveEngineProvider,
                          ObjectProvider<NotificationService> proactiveNotificationServiceProvider,
                          Executor executor) {
        this(agent, objectMapper, memoryStore, ticketStore, rateLimiter, heartbeat, agentMetrics,
                characterStateService, insightServiceProvider, cognitiveEngineProvider,
                proactiveNotificationServiceProvider,
                AgentTurnCoordinator.inMemory(), new AgentProperties(), executor);
    }

    AgentWebSocketHandler(ChthollyAgent agent,
                          ObjectMapper objectMapper,
                          AgentMemoryStore memoryStore,
                          AgentWsTicketStore ticketStore,
                          AgentSessionRateLimiter rateLimiter,
                          AgentWebSocketHeartbeat heartbeat,
                          AgentMetrics agentMetrics,
                          CharacterStateService characterStateService,
                          ObjectProvider<InsightService> insightServiceProvider,
                          ObjectProvider<CognitiveEngine> cognitiveEngineProvider,
                          ObjectProvider<NotificationService> proactiveNotificationServiceProvider,
                          AgentTurnCoordinator turnCoordinator,
                          AgentProperties properties,
                          Executor executor) {
        this.agent = agent;
        this.objectMapper = objectMapper;
        this.memoryStore = memoryStore;
        this.ticketStore = ticketStore;
        this.rateLimiter = rateLimiter;
        this.heartbeat = heartbeat;
        this.agentMetrics = agentMetrics;
        this.characterStateService = characterStateService;
        this.insightServiceProvider = insightServiceProvider;
        this.cognitiveEngineProvider = cognitiveEngineProvider;
        this.proactiveNotificationServiceProvider = proactiveNotificationServiceProvider;
        this.turnCoordinator = turnCoordinator;
        this.properties = properties;
        this.executor = executor;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = authenticate(session);
        if (userId == null) {
            sendJson(session, "error", objectMapper.createObjectNode().put("message", "未授权，请先登录"));
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Unauthorized"));
            return;
        }
        String correlationId = CorrelationIdSupport.generate();
        session.getAttributes().put(CorrelationIdSupport.MDC_CORRELATION_ID, correlationId);
        CorrelationIdSupport.putHttp(correlationId, "WS", session.getUri() == null ? "/api/v1/agent/ws" : session.getUri().getPath());
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT);
        safeSessions.put(session.getId(), safe);
        sessionUsers.put(session.getId(), userId);
        sessionConnectedAt.put(session.getId(), System.currentTimeMillis());
        NotificationService proactiveNotificationService = proactiveNotificationServiceProvider.getIfAvailable();
        if (proactiveNotificationService != null) {
            proactiveNotificationService.registerSession(
                    userId, session.getId(), notification -> sendProactiveNotification(safe, notification));
            sendPendingProactiveNotifications(proactiveNotificationService, userId, safe);
        }
        heartbeat.start(safe);
        agentMetrics.wsConnected();
        log.info("[{}] [AgentWS] Connected: userId={}, sessionId={}", correlationId, userId, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = sessionUsers.get(session.getId());
        if (userId == null) {
            return;
        }
        Set<FutureTask<Void>> tasks = connectionTasks.computeIfAbsent(
                session.getId(), ignored -> ConcurrentHashMap.newKeySet());
        FutureTask<Void> task = new FutureTask<>(() -> {
            String correlationId = CorrelationIdSupport.generate();
            String path = session.getUri() == null ? "/api/v1/agent/ws" : session.getUri().getPath();
            CorrelationIdSupport.runWithContext(
                    CorrelationIdSupport.context(correlationId, "WS", path),
                    () -> handlePayload(session, userId, message.getPayload()));
        }, null) {
            @Override
            protected void done() {
                tasks.remove(this);
                if (tasks.isEmpty()) {
                    connectionTasks.remove(session.getId(), tasks);
                }
            }
        };
        tasks.add(task);
        executor.execute(task);
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        heartbeat.recordPong(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = sessionUsers.remove(session.getId());
        Long connectedAt = sessionConnectedAt.remove(session.getId());
        String chatSessionId = sessionChatSessionIds.remove(session.getId());
        cancelConnectionTurns(session.getId());
        cancelConnectionTasks(session.getId());
        reflectOnConversation(userId, chatSessionId);
        triggerCognitiveCycleIfDue();
        NotificationService proactiveNotificationService = proactiveNotificationServiceProvider.getIfAvailable();
        if (proactiveNotificationService != null) {
            proactiveNotificationService.unregisterSession(userId, session.getId());
        }
        safeSessions.remove(session.getId());
        rateLimiter.removeSession(session.getId());
        heartbeat.stop(session.getId());
        agentMetrics.wsDisconnected();
        long durationSec = connectedAt == null ? 0 : (System.currentTimeMillis() - connectedAt) / 1000;
        String correlationId = (String) session.getAttributes().get(CorrelationIdSupport.MDC_CORRELATION_ID);
        log.info("[{}] [AgentWS] Disconnected: userId={}, sessionId={}, duration={}s",
                correlationId, userId, session.getId(), durationSec);
        MDC.clear();
    }

    private void handlePayload(WebSocketSession session, long userId, String payload) {
        WebSocketSession safe = resolveSession(session);
        try {
            JsonNode root = objectMapper.readTree(payload);
            String type = root.path("type").asText("");

            if ("clear".equals(type)) {
                String chatSessionId = root.path("sessionId").asText("").trim();
                if (!AgentChatSessionSupport.isValid(chatSessionId)) {
                    sendJson(safe, "error", objectMapper.createObjectNode().put("message", "缺少或无效的 sessionId"));
                    return;
                }
                memoryStore.clearMemory(userId, chatSessionId);
                sendJson(
                        safe,
                        "cleared",
                        objectMapper.createObjectNode().put("message", "对话记忆已清空"),
                        requestId(root),
                        null);
                return;
            }

            if (!"chat".equals(type)) {
                sendJson(safe, "error", objectMapper.createObjectNode().put("message", "未知消息类型"));
                return;
            }

            String requestId = requestId(root);
            if (!isValidRequestId(requestId)) {
                sendRejected(safe, requestId, "INVALID_REQUEST_ID", "requestId 格式无效");
                return;
            }

            if (!rateLimiter.tryAcquireChat(session.getId())) {
                sendRejected(safe, requestId, "RATE_LIMITED", "发送过于频繁，请稍后重试");
                return;
            }

            String text = root.path("message").asText("").trim();
            if (text.isEmpty()) {
                sendRejected(safe, requestId, "EMPTY_MESSAGE", "消息不能为空");
                return;
            }

            String chatSessionId = root.path("sessionId").asText("").trim();
            if (!AgentChatSessionSupport.isValid(chatSessionId)) {
                sendRejected(safe, requestId, "INVALID_SESSION_ID", "缺少或无效的 sessionId");
                return;
            }
            sessionChatSessionIds.put(session.getId(), chatSessionId);

            String turnId = UUID.randomUUID().toString();
            int timeoutSeconds = Math.max(1, properties.getTurnTimeoutSeconds());
            Duration turnTimeout = Duration.ofSeconds(timeoutSeconds);
            AgentTurnCoordinator.AcquireResult acquisition = turnCoordinator.acquire(
                    userId,
                    chatSessionId,
                    requestId,
                    turnId,
                    turnTimeout.plusSeconds(TURN_LEASE_GRACE_SECONDS));
            if (acquisition.status() != AgentTurnCoordinator.AcquireStatus.ACQUIRED) {
                String reason = switch (acquisition.status()) {
                    case TURN_IN_PROGRESS -> "TURN_IN_PROGRESS";
                    case DUPLICATE_REQUEST -> "DUPLICATE_REQUEST";
                    case UNAVAILABLE -> "TURN_COORDINATION_UNAVAILABLE";
                    case ACQUIRED -> throw new IllegalStateException("unreachable acquire status");
                };
                sendRejected(safe, requestId, reason, rejectionMessage(reason));
                return;
            }

            AgentTurnControl turnControl = AgentTurnControl.create(
                    requestId,
                    turnId,
                    chatSessionId,
                    session.getId(),
                    turnTimeout);
            ActiveTurn activeTurn = new ActiveTurn(userId, chatSessionId, turnControl);
            connectionTurns.computeIfAbsent(
                            session.getId(), ignored -> new ConcurrentHashMap<>())
                    .put(turnId, activeTurn);

            AtomicReference<AgentEvent> terminalEvent = new AtomicReference<>();
            boolean acceptedSent = false;
            boolean leaseReleased = false;
            try {
                sendJson(
                        safe,
                        "accepted",
                        objectMapper.createObjectNode().put("status", "accepted"),
                        requestId,
                        turnId);
                acceptedSent = true;
                AgentConversationMemory memory = memoryStore.getOrCreateMemory(userId, chatSessionId);
                String pageContext = formatPageContext(root.path("context"));
                String taskType = root.path("taskType").asText("").strip();
                Consumer<AgentEvent> eventSink = event -> {
                    if (!isActiveTurn(session.getId(), turnId, activeTurn) || !safe.isOpen()) {
                        turnControl.cancel();
                        throw AgentTurnBudget.unavailableForStage(
                                AgentTurnBudget.UnavailableReason.CANCELLED,
                                "client_delivery");
                    }
                    if ("final".equals(event.type()) || "error".equals(event.type())) {
                        terminalEvent.set(event);
                        return;
                    }
                    try {
                        sendJson(safe, event.type(), event.data(), requestId, turnId);
                    } catch (Exception e) {
                        log.warn("WebSocket 发送失败: {}", e.getMessage());
                        turnControl.cancel();
                        throw AgentTurnBudget.unavailableForStage(
                                AgentTurnBudget.UnavailableReason.CANCELLED,
                                "client_delivery");
                    }
                };
                String path = session.getUri() == null
                        ? "/api/v1/agent/ws"
                        : session.getUri().getPath();
                CorrelationIdSupport.runWithContext(
                        CorrelationIdSupport.context(turnId, "WS", path),
                        () -> agent.run(
                                text,
                                userId,
                                memory,
                                turnControl,
                                pageContext,
                                taskType,
                                eventSink));
            } catch (Exception exception) {
                log.warn("Agent turn failed turnId={}: {}", turnId, exception.getMessage());
                if (acceptedSent && !turnControl.isCancelled()) {
                    terminalEvent.set(new AgentEvent(
                            "error",
                            objectMapper.createObjectNode()
                                    .put("code", "TURN_FAILED")
                                    .put("message", "处理失败，请稍后重试")));
                } else {
                    turnControl.cancel();
                }
            } finally {
                leaseReleased = turnCoordinator.release(userId, chatSessionId, turnId);
                if (leaseReleased) {
                    removeActiveTurn(session.getId(), turnId, activeTurn);
                }
            }
            AgentEvent terminal = terminalEvent.get();
            if (!leaseReleased) {
                turnControl.cancel();
                terminal = new AgentEvent(
                        "error",
                        objectMapper.createObjectNode()
                                .put("code", "TURN_COORDINATION_UNAVAILABLE")
                                .put("message", "回答已停止，请稍后重试"));
            } else if (terminal == null && acceptedSent && !turnControl.isCancelled()) {
                terminal = new AgentEvent(
                        "error",
                        objectMapper.createObjectNode()
                                .put("code", "TURN_FAILED")
                                .put("message", "处理失败，请稍后重试"));
            }
            if (terminal != null && safe.isOpen()) {
                try {
                    sendJson(safe, terminal.type(), terminal.data(), requestId, turnId);
                    turnControl.completeClientDelivery(
                            true,
                            terminal.type(),
                            terminal.data() == null ? null : terminal.data().path("code").asText(""));
                } catch (Exception exception) {
                    turnControl.cancel();
                    turnControl.completeClientDelivery(false, terminal.type(), "CLIENT_DELIVERY_FAILED");
                    agentMetrics.recordError("client_delivery");
                    log.warn("WebSocket 终态发送失败 turnId={}: {}", turnId, exception.getMessage());
                    closeAfterTerminalFailure(safe, turnId);
                }
            } else if (terminal != null || turnControl.isCancelled()) {
                turnControl.completeClientDelivery(false, terminal == null ? "" : terminal.type(), "CLIENT_DELIVERY_FAILED");
            }
            if (!leaseReleased) {
                agentMetrics.recordError("turn_coordination_release");
                closeAfterTerminalFailure(safe, turnId);
            }
            scheduleCharacterStateUpdate(userId, text);
        } catch (Exception e) {
            log.warn("Agent WS 处理失败: {}", e.getMessage());
            try {
                sendJson(safe, "error", objectMapper.createObjectNode().put("message", "处理失败"));
            } catch (Exception sendEx) {
                log.warn("Failed to send WS error response: {}", sendEx.getMessage());
            }
        }
    }

    private String formatPageContext(JsonNode context) {
        if (context == null || context.isMissingNode() || context.isNull() || !context.isObject()) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        appendTextContext(lines, "页面", context.path("page").asText(""));
        appendTextContext(lines, "标题", context.path("title").asText(""));
        appendTextContext(lines, "来源", context.path("source").asText(""));
        appendTextContext(lines, "postSlug", context.path("postSlug").asText(""));
        appendTextContext(lines, "postId", context.path("postId").asText(""));
        JsonNode tags = context.path("tags");
        if (tags.isArray()) {
            List<String> tagNames = new ArrayList<>();
            for (JsonNode tag : tags) {
                String value = tag.asText("").trim();
                if (!value.isEmpty()) {
                    tagNames.add(value);
                }
            }
            if (!tagNames.isEmpty()) {
                lines.add("标签：" + String.join("、", tagNames));
            }
        }
        return String.join("\n", lines);
    }

    private void reflectOnConversation(Long userId, String chatSessionId) {
        if (userId == null || !AgentChatSessionSupport.isValid(chatSessionId)) {
            return;
        }
        InsightService insightService = insightServiceProvider.getIfAvailable();
        if (insightService == null) {
            return;
        }
        try {
            List<AgentTurn> conversation = memoryStore.getTurns(userId, chatSessionId);
            insightService.reflectOnConversation(userId, conversation);
        } catch (Exception e) {
            log.warn("Agent insight reflection scheduling failed userId={}, sessionId={}: {}",
                    userId, chatSessionId, e.getMessage());
        }
    }

    private void triggerCognitiveCycleIfDue() {
        CognitiveEngine cognitiveEngine = cognitiveEngineProvider.getIfAvailable();
        if (cognitiveEngine == null) {
            return;
        }
        executor.execute(() -> {
            try {
                cognitiveEngine.triggerIfDue();
            } catch (Exception e) {
                log.warn("Agent cognitive cycle scheduling failed: {}", e.getMessage(), e);
            }
        });
    }

    private void appendTextContext(List<String> lines, String label, String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.isEmpty()) {
            lines.add(label + "：" + trimmed);
        }
    }

    private void sendPendingProactiveNotifications(
            NotificationService proactiveNotificationService, Long userId, WebSocketSession session) {
        for (Notification notification : proactiveNotificationService.getPendingNotifications(userId)) {
            sendProactiveNotification(session, notification);
        }
    }

    private void sendProactiveNotification(WebSocketSession session, Notification notification) {
        try {
            if (session.isOpen()) {
                ObjectNode data = objectMapper.createObjectNode();
                data.put("type", notification.type());
                data.put("message", notification.message());
                if (notification.timestamp() != null) {
                    data.put("timestamp", notification.timestamp().toString());
                }
                if (notification.channel() != null) {
                    data.put("channel", notification.channel().name());
                }
                sendJson(session, "proactive", data);
            }
        } catch (Exception e) {
            log.warn("Send proactive notification failed: {}", e.getMessage(), e);
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
            int eq = part.indexOf('=');
            if (eq > 0 && name.equals(part.substring(0, eq))) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private WebSocketSession resolveSession(WebSocketSession session) {
        return safeSessions.getOrDefault(session.getId(), session);
    }

    private boolean isActiveTurn(String connectionId, String turnId, ActiveTurn expected) {
        if (expected.control().isCancelled()) {
            return false;
        }
        Map<String, ActiveTurn> turns = connectionTurns.get(connectionId);
        return turns != null && turns.get(turnId) == expected;
    }

    private void removeActiveTurn(String connectionId, String turnId, ActiveTurn expected) {
        Map<String, ActiveTurn> turns = connectionTurns.get(connectionId);
        if (turns == null) {
            return;
        }
        turns.remove(turnId, expected);
        if (turns.isEmpty()) {
            connectionTurns.remove(connectionId, turns);
        }
    }

    private void cancelConnectionTurns(String connectionId) {
        Map<String, ActiveTurn> turns = connectionTurns.remove(connectionId);
        if (turns == null) {
            return;
        }
        for (ActiveTurn active : turns.values()) {
            active.control().cancel();
            active.control().completeClientDelivery(false, "", "CLIENT_DISCONNECTED");
            turnCoordinator.release(active.userId(), active.chatSessionId(), active.control().turnId());
        }
    }

    private void scheduleCharacterStateUpdate(long userId, String text) {
        Instant occurredAt = Instant.now();
        try {
            executor.execute(() -> {
                try {
                    characterStateService.updateEmotion(userId, text, occurredAt);
                    characterStateService.recordInteraction(userId);
                } catch (RuntimeException exception) {
                    log.warn("Agent character state update failed: {}", exception.getMessage());
                }
            });
        } catch (RuntimeException exception) {
            log.warn("Agent character state update scheduling failed: {}", exception.getMessage());
        }
    }

    private void closeAfterTerminalFailure(WebSocketSession session, String turnId) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR.withReason("Agent turn delivery failed"));
            }
        } catch (Exception closeException) {
            log.warn("WebSocket 关闭失败 turnId={}: {}", turnId, closeException.getMessage());
        }
    }

    private void cancelConnectionTasks(String connectionId) {
        Set<FutureTask<Void>> tasks = connectionTasks.remove(connectionId);
        if (tasks == null) {
            return;
        }
        for (FutureTask<Void> task : tasks) {
            task.cancel(true);
        }
    }

    private String requestId(JsonNode root) {
        return root.path("requestId").asText("").strip();
    }

    private boolean isValidRequestId(String requestId) {
        return requestId != null && REQUEST_ID_PATTERN.matcher(requestId).matches();
    }

    private void sendRejected(
            WebSocketSession session,
            String requestId,
            String code,
            String message) throws Exception {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("code", code);
        data.put("message", message);
        sendJson(session, "rejected", data, requestId, null);
    }

    private String rejectionMessage(String code) {
        return switch (code) {
            case "TURN_IN_PROGRESS" -> "当前对话仍有回答正在生成";
            case "DUPLICATE_REQUEST" -> "该请求已经处理过，请勿重复发送";
            case "TURN_COORDINATION_UNAVAILABLE" -> "暂时无法建立回答任务，请稍后重试";
            default -> "请求未被接受";
        };
    }

    private void sendJson(WebSocketSession session, String type, JsonNode data) throws Exception {
        sendJson(session, type, data, null, null);
    }

    private void sendJson(
            WebSocketSession session,
            String type,
            JsonNode data,
            String requestId,
            String turnId) throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", type);
        if (requestId != null && !requestId.isBlank()) {
            envelope.put("requestId", requestId);
        }
        if (turnId != null && !turnId.isBlank()) {
            envelope.put("turnId", turnId);
        }
        envelope.set("data", data);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
    }

    private record ActiveTurn(
            long userId,
            String chatSessionId,
            AgentTurnControl control) {
    }
}
