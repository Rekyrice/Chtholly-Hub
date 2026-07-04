package com.chtholly.agent;

import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.context.ContextEngine;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentMetrics;
import com.chtholly.agent.trace.TracePersistenceService;
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
 * Custom ReAct agent engine: Think → Act → Observe loop until final answer or step limit.
 *
 * <p>Design: LLM calls run on virtual threads with configurable timeout; tool execution
 * is isolated with per-tool timeout. Final answers stream over WebSocket with optional
 * character throttling. Observability via {@link AgentExecutionTrace} and {@link AgentMetrics}.
 *
 * @see AgentTool
 * @see AgentConversationMemory
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
    private final CharacterSoulService characterSoulService;
    private final ContextEngine contextEngine;
    private final TracePersistenceService tracePersistenceService;

    /**
     * Runs one agent turn, emitting think/act/observe/delta/final/error events via sink.
     *
     * @param question User question for this turn.
     * @param userId   Authenticated user ID (passed to tools).
     * @param memory   Session conversation memory for follow-up questions.
     * @param sink     Event consumer (typically WebSocket handler).
     */
    public void run(String question, long userId, AgentConversationMemory memory, Consumer<AgentEvent> sink) {
        run(question, userId, memory, null, null, sink);
    }

    /**
     * Runs one agent turn with session ID for observability tracing.
     *
     * @param sessionId WebSocket session identifier (may be null).
     */
    public void run(String question, long userId, AgentConversationMemory memory, String sessionId,
                    Consumer<AgentEvent> sink) {
        run(question, userId, memory, sessionId, null, sink);
    }

    /**
     * Runs one agent turn with session ID and page context for prompt assembly.
     *
     * @param sessionId   WebSocket session identifier (may be null).
     * @param pageContext Current page context sent by the client (may be null).
     */
    public void run(String question, long userId, AgentConversationMemory memory, String sessionId,
                    String pageContext, Consumer<AgentEvent> sink) {
        int maxSteps = Math.max(1, properties.getMaxSteps());
        AgentExecutionTrace trace = new AgentExecutionTrace(userId, sessionId, maxSteps);
        try {
            if (question == null || question.isBlank()) {
                trace.terminateError();
                trace.setErrorMessage("问题不能为空");
                emitError(sink, "问题不能为空");
                return;
            }

            // 工具注册表：按 name 索引，供 ReAct 循环 O(1) 查找
            Map<String, AgentTool> toolMap = new LinkedHashMap<>();
            for (AgentTool tool : tools) {
                toolMap.put(tool.name(), tool);
            }

            String historyBlock = memory == null ? "" : memory.formatForPrompt();
            String system = contextEngine.buildSystemPrompt(
                    userId,
                    sessionId,
                    pageContext,
                    toolMap.values(),
                    historyBlock,
                    question.trim());
            List<String> transcript = new ArrayList<>();
            if (!historyBlock.isBlank()) {
                transcript.add(historyBlock);
            }
            transcript.add("## 当前问题\nUser: " + question.trim());

            // ReAct 主循环：每步 LLM 输出 JSON action，要么调工具要么 final
            for (int step = 0; step < maxSteps; step++) {
                String userPrompt = String.join("\n\n", transcript);
                long stepLlmStart = System.currentTimeMillis();
                String llmOut;
                try {
                    // Think 阶段：低 temperature(0.1) 保证 JSON 结构稳定，便于 parseAction
                    llmOut = callLlm(system, userPrompt);
                } catch (TimeoutException e) {
                    log.warn("Agent LLM 调用超时 (>{}s)", properties.getLlmTimeoutSeconds());
                    trace.terminateTimeout();
                    trace.setErrorMessage("模型响应超时，请稍后重试");
                    emitError(sink, "模型响应超时，请稍后重试");
                    return;
                } catch (Exception e) {
                    log.warn("Agent LLM 调用失败: {}", e.getMessage());
                    trace.terminateError();
                    trace.setErrorMessage("模型调用失败，请检查 LLM 配置");
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
                    trace.setErrorMessage("无法解析模型输出，请重试");
                    emitError(sink, "无法解析模型输出，请重试");
                    return;
                }

                emitThink(sink, action);

                if (action.isFinal()) {
                    // final 不直接用 LLM 的 answer 字段，而是另开流式调用生成用户可见回答
                    // 原因：ReAct 阶段的 JSON 只负责决策，最终回答需要更高 temperature 与自然语言风格
                    long streamLlmMs = streamFinalAnswer(sink, question, transcript, memory, trace);
                    trace.recordStep(step, "final_answer", stepLlmMs + streamLlmMs, 0);
                    return;
                }

                AgentTool tool = toolMap.get(action.action());
                if (tool == null) {
                    // 未知工具：把 Observation 写回 transcript，让 LLM 下一步自我纠正，而非直接失败
                    String observation = "未知工具：" + action.action() + "。请使用已注册工具或返回 final。";
                    emitAct(sink, action.action(), action.input());
                    emitObserve(sink, observation);
                    transcript.add("Assistant: " + llmOut);
                    transcript.add("Observation: " + observation);
                    trace.recordStep(step, action.action(), stepLlmMs, 0);
                    continue;
                }

                Map<String, Object> inputMap = new LinkedHashMap<>(jsonToMap(action.input()));
                // 注入隐式上下文：工具不依赖 LLM 复述完整对话，由引擎注入原始问题与历史
                inputMap.put("_userQuestion", question.trim());
                if (memory != null) {
                    inputMap.put("_conversationHistory", memory.formatForPrompt());
                }
                emitAct(sink, tool.name(), action.input());
                long toolStart = System.currentTimeMillis();
                String observation = executeTool(tool, inputMap, userId);
                long stepToolMs = System.currentTimeMillis() - toolStart;
                trace.recordToolCall(tool.name(), stepToolMs, summarizeToolInput(action.input()), observation);
                // 站内搜索空结果时追加系统提示，引导 LLM 切换 Bangumi 工具（避免重复无效检索）
                observation = augmentObservation(tool.name(), observation);
                emitObserve(sink, observation);
                // Observe 写回 transcript，形成下一轮 Think 的上下文（经典 ReAct 模式）
                transcript.add("Assistant: " + llmOut);
                transcript.add("Observation: " + observation);
                trace.recordStep(step, tool.name(), stepLlmMs, stepToolMs);
            }

            trace.terminateMaxSteps();
            trace.setErrorMessage("已达到最大推理步数（" + maxSteps + "），请简化问题后重试");
            emitError(sink, "已达到最大推理步数（" + maxSteps + "），请简化问题后重试");
        } finally {
            trace.finish();
            trace.finishAndLog(objectMapper, agentMetrics);
            tracePersistenceService.persist(trace);
        }
    }

    /**
     * Streams the final natural-language answer to the client and persists turn to memory.
     *
     * @return LLM streaming duration in milliseconds.
     */
    private long streamFinalAnswer(
            Consumer<AgentEvent> sink,
            String question,
            List<String> transcript,
            AgentConversationMemory memory,
            AgentExecutionTrace trace) {
        String context = String.join("\n\n", transcript);
        String system = """
                ## 你的身份

                %s

                ## 回答准则

                根据对话历史、当前问题与工具 Observation 用简洁中文直接回答用户。
                用户可能在追问上文（如「他们是谁」「宿舍伙伴有哪些」），请结合历史理解指代。
                只陈述 Observation 中有依据的事实；若工具未返回数据请如实说明。
                不要输出 JSON 或 markdown 代码块。""".formatted(characterSoulService.getSoulContent());
        String userPrompt = context + "\n\n请回答用户的当前问题。";

        int timeoutSec = Math.max(1, properties.getLlmTimeoutSeconds());
        long streamStart = System.currentTimeMillis();
        try {
            Flux<String> flux = chatClient.prompt()
                    .system(system)
                    .user(userPrompt)
                    // final 阶段 temperature 0.3：比 ReAct 决策略高，回答更自然
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
                trace.setErrorMessage("生成回答超时，请稍后重试");
                emitError(sink, "生成回答超时，请稍后重试");
                return streamMs;
            }
            log.warn("Agent 流式回答失败: {}", e.getMessage());
            trace.terminateError();
            trace.setErrorMessage("生成回答失败，请重试");
            emitError(sink, "生成回答失败，请重试");
            return streamMs;
        }
    }

    /** Executes a tool on a virtual thread with configurable timeout; cancels on timeout. */
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

    /** Blocking LLM call on virtual thread; used for ReAct JSON action parsing (non-streaming). */
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

    /** Emits delta chunks character-by-character for typewriter UX on WebSocket clients. */
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

    private boolean isBangumiTool(String toolName) {
        return toolName != null && toolName.startsWith("bangumi_");
    }

    private boolean isEmptySiteResult(String observation) {
        if (observation == null) {
            return true;
        }
        return observation.contains("未找到与")
                || observation.contains("未找到相关")
                || observation.contains("向量库中未找到");
    }

    private boolean isToolTimeout(String observation) {
        return observation != null && observation.contains("Tool execution timed out");
    }

    /** Adds gentle follow-up guidance to Observation for the next ReAct step. */
    private String augmentObservation(String toolName, String observation) {
        String result = observation == null ? "" : observation;
        if (isEmptySiteResult(result) && isSiteTool(toolName)) {
            result = result + """

                    站内没有找到相关帖子呢。如果用户问的是动漫条目事实（评分、季数、角色、作者作品等），下一步请试着用 bangumi_search、bangumi_characters 或 bangumi_person_works，不要重复同样的站内检索。""";
        }
        if (isToolTimeout(result) && isBangumiTool(toolName)) {
            result = result + """

                    Bangumi 查询刚才有点慢。请用相同或更简短的 keyword 再试一次；如果还是超时，就如实告诉用户暂时查不到，不要编造数据。""";
        }
        return result;
    }

    private String summarizeToolInput(JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return "";
        }
        try {
            String json = objectMapper.writeValueAsString(input);
            return json.length() <= 256 ? json : json.substring(0, 256);
        } catch (Exception e) {
            return input.toString();
        }
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
