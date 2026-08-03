package com.chtholly.agent;

import com.fasterxml.jackson.databind.JsonNode;

/** 解析 LLM 输出的动作。 */
public record AgentAction(String action, JsonNode input) {

    public boolean isFinal() {
        return "final".equalsIgnoreCase(action);
    }
}
