package com.chtholly.agent.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条前端会话的对话记忆；{@link #add} 委托 {@link AgentMemoryStore} 写入 Redis List。
 */
public class AgentConversationMemory {

    private final long userId;
    private final String chatSessionId;
    private final AgentMemoryStore store;
    private final List<AgentTurn> turns;

    AgentConversationMemory(long userId, String chatSessionId, List<AgentTurn> turns, AgentMemoryStore store) {
        this.userId = userId;
        this.chatSessionId = chatSessionId;
        this.store = store;
        this.turns = new ArrayList<>(turns == null ? List.of() : turns);
    }

    public void add(AgentTurn turn) {
        if (turn == null || turn.content() == null || turn.content().isBlank()) {
            return;
        }
        store.addTurn(userId, chatSessionId, turn);
        turns.add(turn);
        int max = store.maxTurns();
        while (turns.size() > max) {
            turns.remove(0);
        }
    }

    public boolean isEmpty() {
        return turns.isEmpty();
    }

    /** 格式化为注入 LLM 的上下文文本。 */
    public String formatForPrompt() {
        if (turns.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## 对话历史（理解追问、指代如「他们」「宿舍伙伴」）\n");
        for (AgentTurn turn : turns) {
            if (turn.role() == AgentTurn.Role.USER) {
                sb.append("User: ").append(turn.content().trim()).append('\n');
            } else {
                sb.append("Assistant: ").append(turn.content().trim()).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
