package com.chtholly.agent.memory;

/** 一轮对话记录（用户问 / 助手答）。 */
public record AgentTurn(Role role, String content) {

    public enum Role {
        USER, ASSISTANT
    }

    public static AgentTurn user(String content) {
        return new AgentTurn(Role.USER, content);
    }

    public static AgentTurn assistant(String content) {
        return new AgentTurn(Role.ASSISTANT, content);
    }
}
