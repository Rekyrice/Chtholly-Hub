package com.chtholly.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Outbox 消息解析工具。
 *
 * <p>用于消费 Canal 推送的 binlog JSON 消息，从中提取 outbox 表的行数据（INSERT/UPDATE）。</p>
 */
public final class OutboxMessageUtil {
    /**
     * 工具类禁止实例化。
     */
    private OutboxMessageUtil() {}

    /**
     * 从 Canal 消息中提取 outbox 表的变更行。
     *
     * <p>仅处理：</p>
     * <ul>
     *   <li>table = outbox</li>
     *   <li>type ∈ {INSERT, UPDATE}</li>
     *   <li>data 为数组（每个元素是一行记录的列集合）</li>
     * </ul>
     *
     * @param objectMapper Jackson 解析器
     * @param message Canal JSON 消息
     * @return outbox 行数组；合法但无关的表或操作返回空列表
     * @throws IllegalArgumentException 消息不是合法 JSON，或 outbox INSERT/UPDATE 结构不完整
     */
    public static List<JsonNode> extractRows(ObjectMapper objectMapper, String message) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Canal envelope is required");
        }
        try {
            JsonNode root = objectMapper.readTree(message);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Canal envelope must be a JSON object");
            }
            JsonNode table = root.get("table");
            if (table == null || !table.isTextual() || table.asText().isBlank()) {
                throw new IllegalArgumentException("Canal envelope table is required");
            }
            if (!"outbox".equals(table.asText())) {
                return Collections.emptyList();
            }

            JsonNode type = root.get("type");
            if (type == null || !type.isTextual() || type.asText().isBlank()) {
                throw new IllegalArgumentException("Canal Outbox envelope type is required");
            }
            if (!"INSERT".equals(type.asText()) && !"UPDATE".equals(type.asText())) {
                return Collections.emptyList();
            }

            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                throw new IllegalArgumentException(
                        "Canal Outbox envelope data must be an array");
            }
            if (data.isEmpty()) {
                throw new IllegalArgumentException(
                        "Canal Outbox envelope data must contain at least one row");
            }
            List<JsonNode> rows = new ArrayList<>();
            data.forEach(rows::add);
            return List.copyOf(rows);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Canal envelope is not valid JSON", exception);
        }
    }

    /**
     * 从 outbox 行中提取事件 ID。
     */
    public static Long extractEventId(JsonNode row) {
        if (row == null) {
            return null;
        }
        JsonNode idNode = row.get("id");
        if (idNode == null || idNode.isNull()) {
            return null;
        }
        try {
            return Long.parseLong(idNode.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
