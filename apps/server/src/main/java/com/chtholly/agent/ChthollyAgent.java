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
import reactor.core.publisher.Flux;

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
     * 执行一轮对话，通过 sink 推送 think/act/observe/delta/final/error 事件。
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

            AgentAction action;
            try {
                action = parseAction(llmOut);
            } catch (Exception e) {
                emitError(sink, "无法解析模型输出，请重试");
                return;
            }

            emitThink(sink, action);

            if (action.isFinal()) {
                if (isBangumiDomainQuestion(question) && !usedBangumiTool(transcript)) {
                    String observation = authorWorksQuestion(question)
                            ? "系统要求：该问题涉及作者/作品列表，必须先调用 bangumi_person_works（可传 work_title），禁止凭记忆或站内搜索直接回答。"
                            : "系统要求：该问题涉及动漫/漫画元数据，必须先调用 bangumi_search 或 bangumi_person_works，禁止凭记忆直接回答。";
                    emitObserve(sink, observation);
                    transcript.add("Assistant: " + llmOut);
                    transcript.add("Observation: " + observation);
                    continue;
                }
                streamFinalAnswer(sink, question, transcript);
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

            Map<String, Object> inputMap = new LinkedHashMap<>(jsonToMap(action.input()));
            inputMap.put("_userQuestion", question.trim());
            emitAct(sink, tool.name(), action.input());
            String observation;
            try {
                observation = tool.execute(inputMap, userId);
            } catch (Exception e) {
                log.warn("工具 {} 执行失败: {}", tool.name(), e.getMessage());
                observation = "工具执行失败：" + e.getMessage();
            }
            observation = augmentObservation(question, tool.name(), observation);
            emitObserve(sink, observation);
            transcript.add("Assistant: " + llmOut);
            transcript.add("Observation: " + observation);
        }

        emitError(sink, "已达到最大推理步数（" + maxSteps + "），请简化问题后重试");
    }

    private void streamFinalAnswer(Consumer<AgentEvent> sink, String question, List<String> transcript) {
        String context = String.join("\n\n", transcript);
        String system = """
                你是 Chtholly Hub 的动漫知识助手「珂朵莉」。
                根据上方对话与工具 Observation 用简洁中文直接回答用户。
                只陈述 Observation 中有依据的事实；若工具未返回数据请如实说明。
                不要输出 JSON 或 markdown 代码块。""";

        try {
            Flux<String> flux = chatClient.prompt()
                    .system(system)
                    .user(context + "\n\n请回答用户的问题。")
                    .options(DeepSeekChatOptions.builder()
                            .model("deepseek-chat")
                            .temperature(0.3)
                            .maxTokens(1024)
                            .build())
                    .stream()
                    .content();

            StringBuilder full = new StringBuilder();
            flux.doOnNext(chunk -> {
                if (chunk != null && !chunk.isEmpty()) {
                    full.append(chunk);
                    emitDelta(sink, chunk);
                }
            }).blockLast();

            emitFinal(sink, full.toString());
        } catch (Exception e) {
            log.warn("Agent 流式回答失败: {}", e.getMessage());
            emitError(sink, "生成回答失败，请重试");
        }
    }

    private boolean hasToolObservation(List<String> transcript) {
        return transcript.stream().anyMatch(line -> line.startsWith("Observation:"));
    }

    private boolean usedBangumiTool(List<String> transcript) {
        return transcript.stream().anyMatch(line ->
                line.contains("bangumi_search") || line.contains("bangumi_person_works"));
    }

    private boolean isSiteTool(String toolName) {
        return "fulltext_search".equals(toolName) || "article_rag".equals(toolName);
    }

    private boolean isEmptySiteResult(String observation) {
        if (observation == null) {
            return true;
        }
        return observation.contains("未找到与")
                || observation.contains("未找到相关")
                || observation.contains("向量库中未找到");
    }

    private String augmentObservation(String question, String toolName, String observation) {
        if (!isEmptySiteResult(observation) || !isBangumiDomainQuestion(question) || !isSiteTool(toolName)) {
            return observation;
        }
        if (authorWorksQuestion(question)) {
            return observation + """

                    [系统提示] 站内无相关帖子。作者/作品列表请用 bangumi_person_works 查 Bangumi。
                    示例：{"action":"bangumi_person_works","input":{"work_title":"少女终末旅行","work_type":"book"}}
                    勿再调用 fulltext_search / article_rag。""";
        }
        return observation + """

                [系统提示] 站内无结果。动漫/漫画元数据请用 bangumi_search 或 bangumi_person_works，勿重复站内搜索。""";
    }

    private boolean authorWorksQuestion(String question) {
        if (question == null) {
            return false;
        }
        return question.contains("作者")
                && (question.contains("作品") || question.contains("漫画") || question.contains("部"));
    }

    /** 应用 Bangumi 而非站内搜索的动漫/漫画元数据问题。 */
    private boolean isBangumiDomainQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.toLowerCase();
        return q.matches(".*(季|季数|几季|集数|几集|多少集|评分|分数|排名|放送|上映|ova|剧场版).*")
                || q.contains("re0")
                || q.contains("re:")
                || q.contains("从零开始")
                || q.contains("bangumi")
                || q.contains("番剧")
                || q.contains("作者")
                || q.contains("漫画")
                || q.contains("作品")
                || q.contains("漫画家")
                || q.contains("声优")
                || q.contains("插画");
    }

    private String buildSystemPrompt(Iterable<AgentTool> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Chtholly Hub 的动漫知识助手「珂朵莉」。");
        sb.append("你可以调用工具获取信息后再回答用户。");
        sb.append("每次回复必须是单个 JSON 对象，不要输出 markdown 代码块或其他多余文字。\n");
        sb.append("调用工具：{\"action\":\"工具名\",\"input\":{...}}\n");
        sb.append("工具完成后：{\"action\":\"final\",\"answer\":\"占位，实际由系统流式生成\"}\n\n");
        sb.append("可用工具：\n");
        for (AgentTool tool : tools) {
            sb.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
        }
        sb.append("""

                工具选择（必须遵守）：
                1. fulltext_search / article_rag：仅搜索本站博客帖子。用户问动漫库事实时不要先用它们。
                2. bangumi_search：查条目（动画/漫画）的评分、季数、集数、放送日。
                3. bangumi_person_works：查作者/漫画家及其全部作品。问「某作者有哪些漫画」「某作品作者还画过什么」必用它。
                4. 涉及 Bangumi 能回答的事实（评分/季数/作者/作品列表）时，禁止凭记忆 final，必须先调 2 或 3。
                5. 只有用户明确问「站内有没有写过…」时才优先 fulltext_search / article_rag。
                6. 获得足够 Observation 后再 action=final；不要编造工具未返回的内容。""");
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

    private void emitThink(Consumer<AgentEvent> sink, AgentAction action) {
        ObjectNode data = objectMapper.createObjectNode();
        if (action.isFinal()) {
            data.put("content", "综合工具结果，生成回答…");
        } else {
            data.put("content", "计划调用工具：" + action.action());
            if (action.input() != null && !action.input().isMissingNode()) {
                data.set("input", action.input());
            }
        }
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

    private void emitDelta(Consumer<AgentEvent> sink, String content) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("content", content);
        AgentEvent.send(sink, "delta", data);
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
