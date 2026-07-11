package com.chtholly.agent.ws;

import com.chtholly.common.tracing.CorrelationIdSupport;
import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.ChthollyAgent;
import com.chtholly.agent.cognitive.CognitiveEngine;
import com.chtholly.agent.learning.InsightService;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentMemoryStore;
import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.notification.NotificationService;
import com.chtholly.agent.observability.AgentMetrics;
import com.chtholly.agent.state.CharacterStateService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Agent WebSocket：客户端发送 chat，服务端推送 ReAct 事件流。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentWebSocketHandler extends TextWebSocketHandler {

    /** 与 {@link com.chtholly.agent.config.AgentWebSocketConfig} 中 sendTimeLimit 一致。 */
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;

    private final ChthollyAgent agent;
    private final ObjectMapper objectMapper;
    private final AgentMemoryStore memoryStore;
    private final AgentWsTicketStore ticketStore;
    private final AgentSessionRateLimiter rateLimiter;
    private final AgentWebSocketHeartbeat heartbeat;
    private final AgentMetrics agentMetrics;
    private final CharacterStateService characterStateService;
    private final InsightService insightService;
    private final ObjectProvider<CognitiveEngine> cognitiveEngineProvider;
    private final NotificationService proactiveNotificationService;
    private final Executor executor;

    /** 原始 sessionId -> 线程安全装饰 session（并发 send 串行化）。 */
    private final Map<String, WebSocketSession> safeSessions = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionUsers = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionConnectedAt = new ConcurrentHashMap<>();
    private final Map<String, String> sessionChatSessionIds = new ConcurrentHashMap<>();

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
     * @param insightService Conversation reflection service.
     * @param cognitiveEngineProvider Optional cognitive engine provider.
     * @param proactiveNotificationService Proactive notification service.
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
                                 InsightService insightService,
                                 ObjectProvider<CognitiveEngine> cognitiveEngineProvider,
                                 NotificationService proactiveNotificationService) {
        // 生产环境继续使用虚拟线程，避免长耗时 Agent 调用阻塞 WebSocket 处理线程。
        this(agent, objectMapper, memoryStore, ticketStore, rateLimiter, heartbeat, agentMetrics,
                characterStateService, insightService, cognitiveEngineProvider, proactiveNotificationService,
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
                          InsightService insightService,
                          ObjectProvider<CognitiveEngine> cognitiveEngineProvider,
                          NotificationService proactiveNotificationService,
                          Executor executor) {
        this.agent = agent;
        this.objectMapper = objectMapper;
        this.memoryStore = memoryStore;
        this.ticketStore = ticketStore;
        this.rateLimiter = rateLimiter;
        this.heartbeat = heartbeat;
        this.agentMetrics = agentMetrics;
        this.characterStateService = characterStateService;
        this.insightService = insightService;
        this.cognitiveEngineProvider = cognitiveEngineProvider;
        this.proactiveNotificationService = proactiveNotificationService;
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
        proactiveNotificationService.registerSession(userId, session.getId(), notification -> sendProactiveNotification(safe, notification));
        sendPendingProactiveNotifications(userId, safe);
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
        executor.execute(() -> {
            String correlationId = (String) session.getAttributes().get(CorrelationIdSupport.MDC_CORRELATION_ID);
            String path = session.getUri() == null ? "/api/v1/agent/ws" : session.getUri().getPath();
            CorrelationIdSupport.runWithContext(
                    CorrelationIdSupport.context(correlationId, "WS", path),
                    () -> handlePayload(session, userId, message.getPayload()));
        });
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
        reflectOnConversation(userId, chatSessionId);
        triggerCognitiveCycleIfDue();
        proactiveNotificationService.unregisterSession(userId, session.getId());
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
                sendJson(safe, "cleared", objectMapper.createObjectNode().put("message", "对话记忆已清空"));
                return;
            }

            if (!"chat".equals(type)) {
                sendJson(safe, "error", objectMapper.createObjectNode().put("message", "未知消息类型"));
                return;
            }

            if (!rateLimiter.tryAcquireChat(session.getId())) {
                sendJson(safe, "error", objectMapper.createObjectNode().put("reason", "RATE_LIMITED"));
                return;
            }

            String text = root.path("message").asText("").trim();
            if (text.isEmpty()) {
                sendJson(safe, "error", objectMapper.createObjectNode().put("message", "消息不能为空"));
                return;
            }

            String chatSessionId = root.path("sessionId").asText("").trim();
            if (!AgentChatSessionSupport.isValid(chatSessionId)) {
                sendJson(safe, "error", objectMapper.createObjectNode().put("message", "缺少或无效的 sessionId"));
                return;
            }
            sessionChatSessionIds.put(session.getId(), chatSessionId);

            AgentConversationMemory memory = memoryStore.getOrCreateMemory(userId, chatSessionId);
            String pageContext = formatPageContext(root.path("context"));
            try {
                agent.run(text, userId, memory, session.getId(), pageContext, event -> {
                    try {
                        if (safe.isOpen()) {
                            sendJson(safe, event.type(), event.data());
                        }
                    } catch (Exception e) {
                        log.warn("WebSocket 发送失败: {}", e.getMessage());
                    }
                });
            } finally {
                characterStateService.updateEmotion(userId, text);
                characterStateService.recordInteraction(userId);
            }
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

    private void sendPendingProactiveNotifications(Long userId, WebSocketSession session) {
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

    private void sendJson(WebSocketSession session, String type, JsonNode data) throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", type);
        envelope.set("data", data);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
    }
}
