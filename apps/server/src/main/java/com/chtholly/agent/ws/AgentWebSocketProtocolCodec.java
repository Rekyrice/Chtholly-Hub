package com.chtholly.agent.ws;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.notification.Notification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Decodes inbound Agent messages and encodes the stable WebSocket protocol.
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentWebSocketProtocolCodec {

    private static final Pattern REQUEST_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final ObjectMapper objectMapper;

    /**
     * Creates a protocol codec.
     *
     * @param objectMapper JSON serializer
     */
    public AgentWebSocketProtocolCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(
                objectMapper, "objectMapper");
    }

    InboundEnvelope decode(String payload) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        return new InboundEnvelope(
                root,
                root.path("type").asText(""),
                root.path("requestId").asText("").strip());
    }

    ChatRequest chatRequest(InboundEnvelope envelope) {
        JsonNode root = envelope.root();
        return new ChatRequest(
                envelope.requestId(),
                root.path("sessionId").asText("").strip(),
                root.path("message").asText("").trim(),
                formatPageContext(root.path("context")),
                root.path("taskType").asText("").strip());
    }

    boolean isValidRequestId(String requestId) {
        return requestId != null
                && REQUEST_ID_PATTERN.matcher(requestId).matches();
    }

    void sendUnauthorized(WebSocketSession session) throws Exception {
        sendEnvelope(
                session,
                "error",
                objectMapper.createObjectNode()
                        .put("message", "未授权，请先登录"),
                null,
                null);
    }

    void sendUnknownType(WebSocketSession session) throws Exception {
        sendEnvelope(
                session,
                "error",
                objectMapper.createObjectNode()
                        .put("message", "未知消息类型"),
                null,
                null);
    }

    void sendGenericError(WebSocketSession session) throws Exception {
        sendEnvelope(
                session,
                "error",
                objectMapper.createObjectNode().put("message", "处理失败"),
                null,
                null);
    }

    void sendRejected(
            WebSocketSession session,
            String requestId,
            String code,
            String message) throws Exception {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("code", code);
        data.put("message", message);
        sendEnvelope(session, "rejected", data, requestId, null);
    }

    void sendRejected(
            WebSocketSession session,
            String requestId,
            String code) throws Exception {
        sendRejected(session, requestId, code, rejectionMessage(code));
    }

    void sendAccepted(
            WebSocketSession session,
            String requestId,
            String turnId) throws Exception {
        sendEnvelope(
                session,
                "accepted",
                objectMapper.createObjectNode().put("status", "accepted"),
                requestId,
                turnId);
    }

    void sendEvent(
            WebSocketSession session,
            AgentEvent event,
            String requestId,
            String turnId) throws Exception {
        sendEnvelope(
                session,
                event.type(),
                event.data(),
                requestId,
                turnId);
    }

    void sendProactive(
            WebSocketSession session,
            Notification notification) throws Exception {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("type", notification.type());
        data.put("message", notification.message());
        if (notification.timestamp() != null) {
            data.put("timestamp", notification.timestamp().toString());
        }
        if (notification.channel() != null) {
            data.put("channel", notification.channel().name());
        }
        sendEnvelope(session, "proactive", data, null, null);
    }

    AgentEvent turnFailedEvent() {
        return errorEvent(
                "TURN_FAILED",
                "处理失败，请稍后重试");
    }

    String terminalCode(AgentEvent terminal) {
        return terminal.data() == null
                ? ""
                : terminal.data().path("code").asText("");
    }

    private AgentEvent errorEvent(String code, String message) {
        return new AgentEvent(
                "error",
                objectMapper.createObjectNode()
                        .put("code", code)
                        .put("message", message));
    }

    private String rejectionMessage(String code) {
        return switch (code) {
            case "TURN_IN_PROGRESS" -> "当前对话仍有回答正在生成";
            case "DUPLICATE_REQUEST" -> "该请求已经处理过，请勿重复发送";
            case "TURN_COORDINATION_UNAVAILABLE" ->
                    "暂时无法建立回答任务，请稍后重试";
            case "MEMORY_UNAVAILABLE" ->
                    "暂时无法加载会话记忆，请稍后重试";
            default -> "请求未被接受";
        };
    }

    private String formatPageContext(JsonNode context) {
        if (context == null
                || context.isMissingNode()
                || context.isNull()
                || !context.isObject()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        appendTextContext(lines, "页面", context.path("page").asText(""));
        appendTextContext(lines, "标题", context.path("title").asText(""));
        appendTextContext(lines, "来源", context.path("source").asText(""));
        appendTextContext(
                lines, "postSlug", context.path("postSlug").asText(""));
        appendTextContext(
                lines, "postId", context.path("postId").asText(""));
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

    private void appendTextContext(
            List<String> lines,
            String label,
            String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.isEmpty()) {
            lines.add(label + "：" + trimmed);
        }
    }

    private void sendEnvelope(
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
        session.sendMessage(new TextMessage(
                objectMapper.writeValueAsString(envelope)));
    }

    record InboundEnvelope(
            JsonNode root,
            String type,
            String requestId) {
    }

    record ChatRequest(
            String requestId,
            String sessionId,
            String message,
            String pageContext,
            String taskType) {
    }
}
