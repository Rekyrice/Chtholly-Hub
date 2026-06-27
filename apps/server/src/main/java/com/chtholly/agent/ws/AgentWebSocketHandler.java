package com.chtholly.agent.ws;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.ChthollyAgent;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentMemoryStore;
import com.chtholly.auth.token.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
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

    private final ChthollyAgent agent;
    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final AgentMemoryStore memoryStore;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, Long> sessionUsers = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = authenticate(session);
        if (userId == null) {
            sendJson(session, "error", objectMapper.createObjectNode().put("message", "未授权，请先登录"));
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Unauthorized"));
            return;
        }
        sessionUsers.put(session.getId(), userId);
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
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionUsers.remove(session.getId());
    }

    private void handlePayload(WebSocketSession session, long userId, String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String type = root.path("type").asText("");

            if ("clear".equals(type)) {
                memoryStore.clear(userId);
                sendJson(session, "cleared", objectMapper.createObjectNode().put("message", "对话记忆已清空"));
                return;
            }

            if (!"chat".equals(type)) {
                sendJson(session, "error", objectMapper.createObjectNode().put("message", "未知消息类型"));
                return;
            }

            String text = root.path("message").asText("").trim();
            if (text.isEmpty()) {
                sendJson(session, "error", objectMapper.createObjectNode().put("message", "消息不能为空"));
                return;
            }

            AgentConversationMemory memory = memoryStore.getOrCreate(userId);
            try {
                agent.run(text, userId, memory, event -> {
                    try {
                        if (session.isOpen()) {
                            sendJson(session, event.type(), event.data());
                        }
                    } catch (Exception e) {
                        log.warn("WebSocket 发送失败: {}", e.getMessage());
                    }
                });
            } finally {
                memoryStore.save(userId, memory);
            }
        } catch (Exception e) {
            log.warn("Agent WS 处理失败: {}", e.getMessage());
            try {
                sendJson(session, "error", objectMapper.createObjectNode().put("message", "处理失败"));
            } catch (Exception ignored) {
            }
        }
    }

    private Long authenticate(WebSocketSession session) {
        try {
            URI uri = session.getUri();
            if (uri == null || uri.getQuery() == null) {
                return null;
            }
            String token = null;
            for (String part : uri.getQuery().split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0 && "token".equals(part.substring(0, eq))) {
                    token = java.net.URLDecoder.decode(part.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                    break;
                }
            }
            if (token == null || token.isBlank()) {
                return null;
            }
            Jwt jwt = jwtService.decode(token);
            if (!"access".equals(jwtService.extractTokenType(jwt))) {
                return null;
            }
            return jwtService.extractUserId(jwt);
        } catch (Exception e) {
            return null;
        }
    }

    private void sendJson(WebSocketSession session, String type, JsonNode data) throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", type);
        envelope.set("data", data);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
    }
}
