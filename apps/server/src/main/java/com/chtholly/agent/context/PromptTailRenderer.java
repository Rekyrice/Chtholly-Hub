package com.chtholly.agent.context;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.memory.AgentTurn;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/** Renders the stable ReAct tool contract and recent conversation. */
@Slf4j
public final class PromptTailRenderer {

    /**
     * Renders available tools, schemas, and the stable tool-use protocol.
     *
     * @param tools tools available to the agent loop
     * @return tool prompt section
     */
    public String renderTools(Iterable<AgentTool> tools) {
        StringBuilder prompt = new StringBuilder("## 可用工具\n\n");
        if (tools != null) {
            try {
                for (AgentTool tool : tools) {
                    appendToolSafely(prompt, tool);
                }
            } catch (RuntimeException e) {
                log.warn("Tool iterable context failed", e);
            }
        }
        prompt.append("## 工具使用准则\n\n")
                .append("1. 优先用工具获取事实，不确定时查一下再回答\n")
                .append("2. 每次只调用一个工具，等结果返回后再决定下一步\n")
                .append("3. 如果站内搜索无结果，尝试 Bangumi 工具搜索动漫相关内容\n")
                .append("4. 不要编造工具返回的数据，如实告诉用户查询结果\n\n")
                .append("输出格式：只输出单个 JSON 对象；调用工具用 {\"action\":\"工具名\",\"input\":{...}}，")
                .append("可以回答时用 {\"action\":\"final\",\"answer\":\"占位\"}");
        return prompt.toString();
    }

    private void appendToolSafely(StringBuilder prompt, AgentTool tool) {
        if (tool == null) {
            log.warn("Skipping null tool in prompt context");
            return;
        }
        try {
            StringBuilder renderedTool = new StringBuilder();
            renderedTool.append("### ").append(tool.name()).append('\n')
                    .append(tool.description());
            appendSchema(renderedTool, tool.parameterSchema());
            renderedTool.append("\n\n");
            prompt.append(renderedTool);
        } catch (RuntimeException e) {
            log.warn("Tool context rendering failed", e);
        }
    }

    /**
     * Renders formatted history, falling back to episodic anchor turns.
     *
     * @param history formatted conversation history
     * @param episodic episodic anchor turns
     * @return history prompt section
     */
    public String renderHistory(String history, List<AgentTurn> episodic) {
        StringBuilder prompt = new StringBuilder("## 对话历史\n\n");
        if (history != null && !history.isBlank()) {
            prompt.append(history.trim());
        } else if (episodic != null && !episodic.isEmpty()) {
            prompt.append(formatTurns(episodic));
        } else {
            prompt.append("（暂无）");
        }
        return prompt.toString();
    }

    private void appendSchema(StringBuilder prompt, Map<String, ParamDef> schema) {
        if (schema == null || schema.isEmpty()) {
            return;
        }
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
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            history.append(turn.role() == AgentTurn.Role.USER ? "User: " : "Assistant: ")
                    .append(turn.content().trim()).append('\n');
        }
        return history.toString().trim();
    }

    private String schemaType(Class<?> type) {
        if (type == String.class) {
            return "string";
        }
        if (type == Integer.class || type == int.class) {
            return "integer";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        return type.getSimpleName();
    }
}
