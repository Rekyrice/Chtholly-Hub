package com.chtholly.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Consumer;

/** Agent 推送给客户端的事件。 */
public record AgentEvent(String type, JsonNode data) {

    public static void send(Consumer<AgentEvent> sink, String type, JsonNode data) {
        if (sink != null) {
            sink.accept(new AgentEvent(type, data));
        }
    }
}
