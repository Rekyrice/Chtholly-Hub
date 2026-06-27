package com.chtholly.agent;

import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentMetrics;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * ReAct 主循环：Think → Act → Observe，直至 final 或达到步数上限。
 * <p>
 * LLM 调用在虚拟线程上执行并带超时；最终流式回答在 WebSocket 虚拟线程执行器中阻塞可接受。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ChthollyAgent {

    private static final ExecutorService LLM_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ChatClient chatClient;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final List<AgentTool> tools;
    private final AgentJsonExtractor jsonExtractor;
    private final AgentMetrics agentMetrics;

    /**
     * 执行一轮对话，通过 sink 推送 think/act/observe/delta/final/error 事件。
     *
     * @param memory 当前 WebSocket 会话记忆（跨轮次追问）
     */
    public void run(String question, long userId, AgentConversationMemory memory, Consumer<AgentEvent> sink) {
        run(question, userId, memory, null, sink);
    }

    /**
     * 执行一轮对话（带 WebSocket sessionId 用于可观测性）。
     */
    public void run(String question, long userId, AgentConversationMemory memory, String sessionId,
                    Consumer<AgentEvent> sink) {
        int maxSteps = Math.max(1, properties.getMaxSteps());
        AgentExecutionTrace trace = new AgentExecutionTrace(userId, sessionId, maxSteps);
        try {
            if (question == null || question.isBlank()) {
                trace.terminateError();
                emitError(sink, "问题不能为空");
                return;
            }

            Map<String, AgentTool> toolMap = new LinkedHashMap<>();
            for (AgentTool tool : tools) {
                toolMap.put(tool.name(), tool);
            }

            String system = buildSystemPrompt(toolMap.values());
            List<String> transcript = new ArrayList<>();
            String historyBlock = memory == null ? "" : memory.formatForPrompt();
            if (!historyBlock.isBlank()) {
                transcript.add(historyBlock);
            }
            transcript.add("## 当前问题\nUser: " + question.trim());

            for (int step = 0; step < maxSteps; step++) {
                String userPrompt = String.join("\n\n", transcript);
                long stepLlmStart = System.currentTimeMillis();
                String llmOut;
                try {
                    llmOut = callLlm(system, userPrompt);
                } catch (TimeoutException e) {
                    log.warn("Agent LLM 调用超时 (>{}s)", properties.getLlmTimeoutSeconds());
                    trace.terminateTimeout();
                    emitError(sink, "模型响应超时，请稍后重试");
                    return;
                } catch (Exception e) {
                    log.warn("Agent LLM 调用失败: {}", e.getMessage());
                    trace.terminateError();
                    emitError(sink, "模型调用失败，请检查 LLM 配置");
                    return;
                }
                long stepLlmMs = System.currentTimeMillis() - stepLlmStart;
                trace.recordLlmCall(stepLlmMs, system.length() + userPrompt.length(), llmOut.length());

                AgentAction action;
                try {
                    action = parseAction(llmOut);
                } catch (Exception e) {
                    trace.recordStep(step, "parse_error", stepLlmMs, 0);
                    trace.terminateError();
                    emitError(sink, "无法解析模型输出，请重试");
                    return;
                }

                emitThink(sink, action);

                if (action.isFinal()) {
                    long streamLlmMs = streamFinalAnswer(sink, question, transcript, memory, trace);
                    trace.recordStep(step, "final_answer", stepLlmMs + streamLlmMs, 0);
                    return;
                }

                AgentTool tool = toolMap.get(action.action());
                if (tool == null) {
                    String observation = "未知工具：" + action.action() + "。请使用已注册工具或返回 final。";
                    emitAct(sink, action.action(), action.input());
                    emitObserve(sink, observation);
                    transcript.add("Assistant: " + llmOut);
                    transcript.add("Observation: " + observation);
                    trace.recordStep(step, action.action(), stepLlmMs, 0);
                    continue;
                }

                Map<String, Object> inputMap = new LinkedHashMap<>(jsonToMap(action.input()));
                inputMap.put("_userQuestion", question.trim());
                if (memory != null) {
                    inputMap.put("_conversationHistory", memory.formatForPrompt());
                }
                emitAct(sink, tool.name(), action.input());
                long toolStart = System.currentTimeMillis();
                String observation = executeTool(tool, inputMap, userId);
                long stepToolMs = System.currentTimeMillis() - toolStart;
                trace.recordToolCall(tool.name(), stepToolMs);
                observation = augmentObservation(tool.name(), observation);
                emitObserve(sink, observation);
                transcript.add("Assistant: " + llmOut);
                transcript.add("Observation: " + observation);
                trace.recordStep(step, tool.name(), stepLlmMs, stepToolMs);
            }

            trace.terminateMaxSteps();
            emitError(sink, "已达到最大推理步数（" + maxSteps + "），请简化问题后重试");
        } finally {
            trace.finishAndLog(objectMapper, agentMetrics);
        }
    }

    /** @return 流式回答 LLM 耗时（毫秒） */
    private long streamFinalAnswer(
            Consumer<AgentEvent> sink,
            String question,
            List<String> transcript,
            AgentConversationMemory memory,
            AgentExecutionTrace trace) {
        String context = String.join("\n\n", transcript);
        String system = """
                你是 Chtholly Hub 的动漫知识助手「珂朵莉」。
                根据对话历史、当前问题与工具 Observation 用简洁中文直接回答用户。
                用户可能在追问上文（如「他们是谁」「宿舍伙伴有哪些」），请结合历史理解指代。
                只陈述 Observation 中有依据的事实；若工具未返回数据请如实说明。
                不要输出 JSON 或 markdown 代码块。""";
        String userPrompt = context + "\n\n请回答用户的当前问题。";

        int timeoutSec = Math.max(1, properties.getLlmTimeoutSeconds());
        long streamStart = System.currentTimeMillis();
        try {
            Flux<String> flux = chatClient.prompt()
                    .system(system)
                    .user(userPrompt)
                    .options(chatOptions(0.3, 1024))
                    .stream()
                    .content()
                    .timeout(Duration.ofSeconds(timeoutSec));

            StringBuilder full = new StringBuilder();
            flux.doOnNext(chunk -> {
                if (chunk != null && !chunk.isEmpty()) {
                    full.append(chunk);
                    emitThrottledDelta(sink, chunk);
                }
            }).blockLast();

            String answer = truncateAnswer(full.toString());
            long streamMs = System.currentTimeMillis() - streamStart;
            trace.recordLlmCall(streamMs, system.length() + userPrompt.length(), answer.length());
            trace.terminateFinalAnswer(answer);
            emitFinal(sink, answer);
            if (memory != null && !answer.isBlank()) {
                memory.add(AgentTurn.user(question.trim()));
                memory.add(AgentTurn.assistant(answer));
            }
            return streamMs;
        } catch (Exception e) {
            long streamMs = System.currentTimeMillis() - streamStart;
            trace.recordLlmCall(streamMs, system.length() + userPrompt.length(), 0);
            if (isTimeout(e)) {
                log.warn("Agent 流式回答超时 (>{}s)", timeoutSec);
                trace.terminateTimeout();
                emitError(sink, "生成回答超时，请稍后重试");
                return streamMs;
            }
            log.warn("Agent 流式回答失败: {}", e.getMessage());
            trace.terminateError();
            emitError(sink, "生成回答失败，请重试");
            return streamMs;
        }
    }

    private String executeTool(AgentTool tool, Map<String, Object> inputMap, long userId) {
        Optional<String> validationError = AgentToolParamValidator.validate(inputMap, tool.parameterSchema());
        if (validationError.isPresent()) {
            return validationError.get();
        }

        int timeoutSec = Math.max(1, properties.getToolTimeoutSeconds());
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> tool.execute(inputMap, userId),
                LLM_EXECUTOR);
        try {
            return future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("工具 {} 执行超时 (>{}s)", tool.name(), timeoutSec);
            return "Tool execution timed out";
        } catch (ExecutionException e) {
            log.warn("工具 {} 执行失败: {}", tool.name(), e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return "工具执行失败：" + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "工具执行被中断";
        }
    }

    private String callLlm(String system, String userPrompt) throws Exception {
        int timeoutSec = Math.max(1, properties.getLlmTimeoutSeconds());
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> chatClient.prompt()
                        .system(system)
                        .user(userPrompt)
                        .options(chatOptions(0.1, 1024))
                        .call()
                        .content(),
                LLM_EXECUTOR);
        try {
            return future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        }
    }

    private DeepSeekChatOptions chatOptions(double temperature, int maxTokens) {
        return DeepSeekChatOptions.builder()
                .model(properties.getModel())
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
    }

    private String truncateAnswer(String answer) {
        if (answer == null || answer.isEmpty()) {
            return "";
        }
        int max = Math.max(1, properties.getMaxResponseChars());
        if (answer.length() <= max) {
            return answer;
        }
        return answer.substring(0, max);
    }

    private static boolean isTimeout(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof TimeoutException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && msg.toLowerCase().contains("timeout")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 按字符节流 delta，避免前端一次性刷完。 */
    private void emitThrottledDelta(Consumer<AgentEvent> sink, String chunk) {
        int delayMs = Math.max(0, properties.getStreamCharDelayMs());
        if (delayMs == 0) {
            emitDelta(sink, chunk);
            return;
        }
        int i = 0;
        while (i < chunk.length()) {
            int cp = chunk.codePointAt(i);
            emitDelta(sink, new String(Character.toChars(cp)));
            i += Character.charCount(cp);
            if (i < chunk.length()) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
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

    /** 站内搜索无结果时提示 LLM 考虑 Bangumi 工具（不做关键词/domain 硬编码判断）。 */
    private String augmentObservation(String toolName, String observation) {
        if (!isEmptySiteResult(observation) || !isSiteTool(toolName)) {
            return observation;
        }
        return observation + """

                [系统提示] 站内无相关帖子。若用户问的是条目元数据（评分、季数、角色、作者作品等），请改用 bangumi_search、bangumi_characters 或 bangumi_person_works；勿重复 fulltext_search / article_rag。""";
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
            sb.append("- ").append(tool.name()).append(": ").append(tool.description());
            appendParameterSchema(sb, tool.parameterSchema());
            sb.append('\n');
        }
        sb.append("""

                工具选择与意图判断（由你理解用户语义，勿凭记忆编造）：
                1. fulltext_search / article_rag：仅当用户明确要搜本站博客帖子、文章片段时使用。
                2. bangumi_search：查 Bangumi 条目（动画/漫画/游戏等）的评分、季数、集数、放送日、排名；统计季数时用系列简称作 keyword，工具会宽召回同系列条目。
                3. bangumi_characters：查某条目的登场角色；keyword 为条目名（追问时可从对话历史推断作品名）。
                4. bangumi_person_works：查作者/漫画家/插画及其参与作品；可传 keyword（人名）与 work_title（作品名）。
                5. 涉及 Bangumi 可查证的事实（评分、季数、角色、作者作品列表等）时，必须先调用 2/3/4 获取 Observation，禁止未调工具就 action=final。
                6. 统计季数时以 Observation 中「共找到 N 部相关动画条目」为准，勿只凭单条推断。
                7. 用户用简称、别名、日文名或中文译名提问时，请自行选择最可能匹配的 keyword 传入工具。
                8. 有对话历史时，短追问（如「他们是谁」「还有谁」）需结合上文确定 keyword 再调工具。
                9. Observation 已足够回答时再 action=final；回答只基于 Observation 与对话历史，不得臆测。""");
        return sb.toString();
    }

    private void appendParameterSchema(StringBuilder sb, Map<String, ParamDef> schema) {
        if (schema == null || schema.isEmpty()) {
            return;
        }
        sb.append("\n  参数：");
        for (Map.Entry<String, ParamDef> entry : schema.entrySet()) {
            ParamDef def = entry.getValue();
            sb.append("\n    - ").append(entry.getKey())
                    .append(" (").append(schemaTypeLabel(def.type()))
                    .append(def.required() ? ", 必填" : ", 可选")
                    .append("): ")
                    .append(def.description());
        }
    }

    private static String schemaTypeLabel(Class<?> type) {
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

    private AgentAction parseAction(String llmOut) throws Exception {
        String json = jsonExtractor.extractActionJson(llmOut);
        JsonNode node = objectMapper.readTree(json);
        String action = node.path("action").asText(null);
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("missing action");
        }
        JsonNode input = node.path("input");
        String answer = node.path("answer").asText(null);
        return new AgentAction(action, input.isMissingNode() ? null : input, answer);
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
