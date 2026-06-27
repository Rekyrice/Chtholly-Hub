package com.chtholly.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 从 LLM 自由文本中提取含 {@code action} 字段的 JSON 对象。
 * 使用 Jackson 流式解析定位完整 JSON 块，避免贪婪正则误匹配。
 */
@Component
public final class AgentJsonExtractor {

    private final ObjectMapper objectMapper;

    public AgentJsonExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 提取第一个合法 action JSON；若存在多个，返回最后一个（模型自我修正时更可靠）。 */
    public String extractActionJson(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("no json found");
        }
        String source = text.trim();

        if (isActionJson(source)) {
            return source;
        }

        String lastValid = null;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) != '{') {
                continue;
            }
            String snippet = readOneJsonObject(source, i);
            if (snippet != null && isActionJson(snippet)) {
                lastValid = snippet;
            }
        }
        if (lastValid != null) {
            return lastValid;
        }
        throw new IllegalArgumentException("no json found");
    }

    private boolean isActionJson(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                return false;
            }
            JsonNode action = node.get("action");
            return action != null && !action.isNull() && !action.asText("").isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private String readOneJsonObject(String source, int start) {
        try (JsonParser parser = objectMapper.getFactory().createParser(source.substring(start))) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            JsonNode node = objectMapper.readTree(parser);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return null;
        }
    }
}
