package com.chtholly.agent.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * WebSocket 会话级短期记忆：保留最近 N 轮问答，供追问与指代消解。
 */
public class AgentConversationMemory {

    private final int maxTurns;
    private final List<AgentTurn> turns = new ArrayList<>();

    public AgentConversationMemory(int maxTurns) {
        this.maxTurns = Math.max(2, maxTurns);
    }

    public synchronized void add(AgentTurn turn) {
        if (turn == null || turn.content() == null || turn.content().isBlank()) {
            return;
        }
        turns.add(turn);
        trim();
    }

    public synchronized List<AgentTurn> snapshot() {
        return List.copyOf(turns);
    }

    public synchronized void clear() {
        turns.clear();
    }

    /** 格式化为注入 LLM 的上下文文本。 */
    public synchronized String formatForPrompt() {
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

    private void trim() {
        while (turns.size() > maxTurns) {
            turns.remove(0);
        }
    }
}
