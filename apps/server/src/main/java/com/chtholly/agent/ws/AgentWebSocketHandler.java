package com.chtholly.agent.ws;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.ChthollyAgent;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentMemoryStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Agent WebSocket：客户端发送 chat，服务端推送 ReAct 事件流。 */
@Slf4j
@Component
@RequiredArgsConstructor
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
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 原始 sessionId -> 线程安全装饰 session（并发 send 串行化）。 */
    private final Map<String, WebSocketSession> safeSessions = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionUsers = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = authenticate(session);
        if (userId == null) {
            sendJson(session, "error", objectMapper.createObjectNode().put("message", "未授权，请先登录"));
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Unauthorized"));
            return;
        }
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT);
        safeSessions.put(session.getId(), safe);
        sessionUsers.put(session.getId(), userId);
        heartbeat.start(safe);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = sessionUsers.get(session.getId());
        if (userId == null) {
            return;
        }
        executor.submit(() -> handlePayload(session, userId, message.getPayload()));
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        heartbeat.recordPong(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionUsers.remove(session.getId());
        safeSessions.remove(session.getId());
        rateLimiter.removeSession(session.getId());
        heartbeat.stop(session.getId());
    }

    private void handlePayload(WebSocketSession session, long userId, String payload) {
        WebSocketSession safe = resolveSession(session);
        try {
            JsonNode root = objectMapper.readTree(payload);
            String type = root.path("type").asText("");

            if ("clear".equals(type)) {
                memoryStore.clearMemory(userId);
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

            AgentConversationMemory memory = memoryStore.getOrCreateMemory(userId);
            agent.run(text, userId, memory, event -> {
                try {
                    if (safe.isOpen()) {
                        sendJson(safe, event.type(), event.data());
                    }
                } catch (Exception e) {
                    log.warn("WebSocket 发送失败: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("Agent WS 处理失败: {}", e.getMessage());
            try {
                sendJson(safe, "error", objectMapper.createObjectNode().put("message", "处理失败"));
            } catch (Exception sendEx) {
                log.warn("Failed to send WS error response: {}", sendEx.getMessage());
            }
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
