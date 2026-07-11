package com.chtholly.agent.context;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.memory.AgentTurn;

import java.util.List;
import java.util.Map;

/** Renders the stable ReAct contract, recent conversation, and current question. */
final class PromptTailRenderer {

    void append(StringBuilder prompt, Iterable<AgentTool> tools,
                String conversationHistory, List<AgentTurn> episodic,
                String userQuestion) {
        appendTools(prompt, tools);
        appendHistory(prompt, conversationHistory, episodic);
        prompt.append("## 用户的问题\n\n")
                .append(userQuestion == null ? "" : userQuestion.trim());
    }

    private void appendTools(StringBuilder prompt, Iterable<AgentTool> tools) {
        prompt.append("## 可用工具\n\n");
        if (tools != null) {
            for (AgentTool tool : tools) {
                prompt.append("### ").append(tool.name()).append('\n')
                        .append(tool.description());
                appendSchema(prompt, tool.parameterSchema());
                prompt.append("\n\n");
            }
        }
        prompt.append("## 工具使用准则\n\n")
                .append("1. 优先用工具获取事实，不确定时查一下再回答\n")
                .append("2. 每次只调用一个工具，等结果返回后再决定下一步\n")
                .append("3. 如果站内搜索无结果，尝试 Bangumi 工具搜索动漫相关内容\n")
                .append("4. 不要编造工具返回的数据，如实告诉用户查询结果\n\n")
                .append("输出格式：只输出单个 JSON 对象；调用工具用 {\"action\":\"工具名\",\"input\":{...}}，")
                .append("可以回答时用 {\"action\":\"final\",\"answer\":\"占位\"}\n\n");
    }

    private void appendHistory(StringBuilder prompt, String history, List<AgentTurn> episodic) {
        prompt.append("## 对话历史\n\n");
        if (history != null && !history.isBlank()) {
            prompt.append(history.trim());
        } else if (episodic != null && !episodic.isEmpty()) {
            prompt.append(formatTurns(episodic));
        } else {
            prompt.append("（暂无）");
        }
        prompt.append("\n\n");
    }

    private void appendSchema(StringBuilder prompt, Map<String, ParamDef> schema) {
        if (schema == null || schema.isEmpty()) return;
        prompt.append("\n  参数：");
        for (Map.Entry<String, ParamDef> entry : schema.entrySet()) {
            ParamDef definition = entry.getValue();
            prompt.append("\n    - ").append(entry.getKey())
                    .append(" (").append(schemaType(definition.type()))
                    .append(definition.required() ? ", 必填" : ", 可选")
                    .append("): ").append(definition.description());
        }
    }

    private String formatTurns(List<AgentTurn> turns) {
        StringBuilder history = new StringBuilder();
        for (AgentTurn turn : turns) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) continue;
            history.append(turn.role() == AgentTurn.Role.USER ? "User: " : "Assistant: ")
                    .append(turn.content().trim()).append('\n');
        }
        return history.toString().trim();
    }

    private String schemaType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == Integer.class || type == int.class) return "integer";
        if (type == Boolean.class || type == boolean.class) return "boolean";
        return type.getSimpleName();
    }
}
