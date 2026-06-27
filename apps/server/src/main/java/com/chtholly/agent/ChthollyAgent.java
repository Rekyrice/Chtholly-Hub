package com.chtholly.agent;

import com.chtholly.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct 主循环：Think → Act → Observe，直至 final 或达到步数上限。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ChthollyAgent {

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*}", Pattern.DOTALL);

    private final ChatClient chatClient;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final List<AgentTool> tools;

    /**
     * 执行一轮对话，通过 sink 推送 think/act/observe/final/error 事件。
     */
    public void run(String question, long userId, Consumer<AgentEvent> sink) {
        if (question == null || question.isBlank()) {
            emitError(sink, "问题不能为空");
            return;
        }

        Map<String, AgentTool> toolMap = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            toolMap.put(tool.name(), tool);
        }

        String system = buildSystemPrompt(toolMap.values());
        List<String> transcript = new ArrayList<>();
        transcript.add("User: " + question.trim());

        int maxSteps = Math.max(1, properties.getMaxSteps());
        for (int step = 0; step < maxSteps; step++) {
            String llmOut;
            try {
                llmOut = chatClient.prompt()
                        .system(system)
                        .user(String.join("\n\n", transcript))
                        .options(DeepSeekChatOptions.builder()
                                .model("deepseek-chat")
                                .temperature(0.1)
                                .maxTokens(1024)
                                .build())
                        .call()
                        .content();
            } catch (Exception e) {
                log.warn("Agent LLM 调用失败: {}", e.getMessage());
                emitError(sink, "模型调用失败，请检查 LLM 配置");
                return;
            }

            emitThink(sink, llmOut);

            AgentAction action;
            try {
                action = parseAction(llmOut);
            } catch (Exception e) {
                emitError(sink, "无法解析模型输出，请重试");
                return;
            }

            if (action.isFinal()) {
                emitFinal(sink, action.answer() == null ? "" : action.answer());
                return;
            }

            AgentTool tool = toolMap.get(action.action());
            if (tool == null) {
                String observation = "未知工具：" + action.action() + "。请使用已注册工具或返回 final。";
                emitAct(sink, action.action(), action.input());
                emitObserve(sink, observation);
                transcript.add("Assistant: " + llmOut);
                transcript.add("Observation: " + observation);
                continue;
            }

            Map<String, Object> inputMap = jsonToMap(action.input());
            emitAct(sink, tool.name(), action.input());
            String observation;
            try {
                observation = tool.execute(inputMap, userId);
            } catch (Exception e) {
                log.warn("工具 {} 执行失败: {}", tool.name(), e.getMessage());
                observation = "工具执行失败：" + e.getMessage();
            }
            emitObserve(sink, observation);
            transcript.add("Assistant: " + llmOut);
            transcript.add("Observation: " + observation);
        }

        emitError(sink, "已达到最大推理步数（" + maxSteps + "），请简化问题后重试");
    }

    private String buildSystemPrompt(Iterable<AgentTool> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Chtholly Hub 的动漫知识助手「珂朵莉」。");
        sb.append("你可以调用工具获取信息后再回答用户。");
        sb.append("每次回复必须是单个 JSON 对象，不要输出 markdown 代码块或其他多余文字。\n");
        sb.append("调用工具：{\"action\":\"工具名\",\"input\":{...}}\n");
        sb.append("最终回答：{\"action\":\"final\",\"answer\":\"你的中文回答\"}\n\n");
        sb.append("可用工具：\n");
        for (AgentTool tool : tools) {
            sb.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
        }
        sb.append("\n优先使用工具获取事实，不要编造站内不存在的内容。");
        return sb.toString();
    }

    private AgentAction parseAction(String llmOut) throws Exception {
        String json = extractJson(llmOut);
        JsonNode node = objectMapper.readTree(json);
        String action = node.path("action").asText(null);
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("missing action");
        }
        JsonNode input = node.path("input");
        String answer = node.path("answer").asText(null);
        return new AgentAction(action, input.isMissingNode() ? null : input, answer);
    }

    private String extractJson(String text) {
        Matcher m = JSON_BLOCK.matcher(text == null ? "" : text.trim());
        if (m.find()) {
            return m.group();
        }
        throw new IllegalArgumentException("no json found");
    }

    private Map<String, Object> jsonToMap(JsonNode input) {
        if (input == null || input.isNull() || input.isMissingNode()) {
            return Map.of();
        }
        return objectMapper.convertValue(input, Map.class);
    }

    private void emitThink(Consumer<AgentEvent> sink, String content) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("content", content);
        AgentEvent.send(sink, "think", data);
    }

    private void emitAct(Consumer<AgentEvent> sink, String tool, JsonNode input) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("tool", tool);
        data.set("input", input == null ? objectMapper.createObjectNode() : input);
        AgentEvent.send(sink, "act", data);
    }

    private void emitObserve(Consumer<AgentEvent> sink, String content) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("content", content);
        AgentEvent.send(sink, "observe", data);
    }

    private void emitFinal(Consumer<AgentEvent> sink, String content) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("content", content);
        AgentEvent.send(sink, "final", data);
    }

    private void emitError(Consumer<AgentEvent> sink, String message) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("message", message);
        AgentEvent.send(sink, "error", data);
    }
}
