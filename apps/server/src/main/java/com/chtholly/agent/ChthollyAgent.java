package com.chtholly.agent;

import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.memory.AgentContextUtil;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentTurn;
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

    /**
     * 执行一轮对话，通过 sink 推送 think/act/observe/delta/final/error 事件。
     *
     * @param memory 当前 WebSocket 会话记忆（跨轮次追问）
     */
    public void run(String question, long userId, AgentConversationMemory memory, Consumer<AgentEvent> sink) {
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
        String historyBlock = memory == null ? "" : memory.formatForPrompt();
        if (!historyBlock.isBlank()) {
            transcript.add(historyBlock);
        }
        transcript.add("## 当前问题\nUser: " + question.trim());

        int maxSteps = Math.max(1, properties.getMaxSteps());
        for (int step = 0; step < maxSteps; step++) {
            String llmOut;
            try {
                llmOut = callLlm(system, String.join("\n\n", transcript));
            } catch (TimeoutException e) {
                log.warn("Agent LLM 调用超时 (>{}s)", properties.getLlmTimeoutSeconds());
                emitError(sink, "模型响应超时，请稍后重试");
                return;
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
                if (needsBangumiToolBeforeFinal(question, memory, transcript)) {
                    String observation = characterQuestion(question)
                            ? "系统要求：该问题涉及条目角色/人物，必须先调用 bangumi_characters（keyword 从对话历史推断作品名），禁止凭记忆直接回答。"
                            : authorWorksQuestion(question)
                            ? "系统要求：该问题涉及作者/作品列表，必须先调用 bangumi_person_works（可传 work_title），禁止凭记忆或站内搜索直接回答。"
                            : "系统要求：该问题涉及动漫/漫画元数据，必须先调用 bangumi_search、bangumi_characters 或 bangumi_person_works，禁止凭记忆直接回答。";
                    emitObserve(sink, observation);
                    transcript.add("Assistant: " + llmOut);
                    transcript.add("Observation: " + observation);
                    continue;
                }
                streamFinalAnswer(sink, question, transcript, memory);
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
            if (memory != null) {
                inputMap.put("_conversationHistory", memory.formatForPrompt());
            }
            emitAct(sink, tool.name(), action.input());
            String observation = executeTool(tool, inputMap, userId);
            observation = augmentObservation(question, tool.name(), observation);
            emitObserve(sink, observation);
            transcript.add("Assistant: " + llmOut);
            transcript.add("Observation: " + observation);
        }

        emitError(sink, "已达到最大推理步数（" + maxSteps + "），请简化问题后重试");
    }

    private void streamFinalAnswer(
            Consumer<AgentEvent> sink,
            String question,
            List<String> transcript,
            AgentConversationMemory memory) {
        String context = String.join("\n\n", transcript);
        String system = """
                你是 Chtholly Hub 的动漫知识助手「珂朵莉」。
                根据对话历史、当前问题与工具 Observation 用简洁中文直接回答用户。
                用户可能在追问上文（如「他们是谁」「宿舍伙伴有哪些」），请结合历史理解指代。
                只陈述 Observation 中有依据的事实；若工具未返回数据请如实说明。
                不要输出 JSON 或 markdown 代码块。""";

        int timeoutSec = Math.max(1, properties.getLlmTimeoutSeconds());
        try {
            Flux<String> flux = chatClient.prompt()
                    .system(system)
                    .user(context + "\n\n请回答用户的当前问题。")
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
            emitFinal(sink, answer);
            if (memory != null && !answer.isBlank()) {
                memory.add(AgentTurn.user(question.trim()));
                memory.add(AgentTurn.assistant(answer));
            }
        } catch (Exception e) {
            if (isTimeout(e)) {
                log.warn("Agent 流式回答超时 (>{}s)", timeoutSec);
                emitError(sink, "生成回答超时，请稍后重试");
                return;
            }
            log.warn("Agent 流式回答失败: {}", e.getMessage());
            emitError(sink, "生成回答失败，请重试");
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

    private boolean usedBangumiTool(List<String> transcript) {
        return transcript.stream().anyMatch(line ->
                line.contains("bangumi_search")
                        || line.contains("bangumi_person_works")
                        || line.contains("bangumi_characters"));
    }

    private boolean needsBangumiToolBeforeFinal(
            String question, AgentConversationMemory memory, List<String> transcript) {
        if (usedBangumiTool(transcript)) {
            return false;
        }
        if (isBangumiDomainQuestion(question)) {
            return true;
        }
        String history = memory == null ? "" : memory.formatForPrompt();
        return isFollowUpQuestion(question) && AgentContextUtil.historyMentionsBangumiTopic(history);
    }

    private boolean characterQuestion(String question) {
        if (question == null) {
            return false;
        }
        return question.contains("人物")
                || question.contains("角色")
                || question.contains("伙伴")
                || question.contains("登场");
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
                || q.contains("插画")
                || q.contains("人物")
                || q.contains("角色")
                || q.contains("伙伴");
    }

    /** 短追问（依赖对话历史理解指代）。 */
    private boolean isFollowUpQuestion(String question) {
        if (question == null) {
            return false;
        }
        String q = question.trim();
        return q.length() <= 20
                || q.contains("他们")
                || q.contains("伙伴")
                || q.contains("还有")
                || q.contains("分别")
                || q.contains("哪些");
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

                工具选择（必须遵守）：
                1. fulltext_search / article_rag：仅搜索本站博客帖子。用户问动漫库事实时不要先用它们。
                2. bangumi_search：查条目（动画/漫画）的评分、季数、集数、放送日。问「有几季」时用系列简称（如「盾之勇者」），工具会返回全部相关动画条目。
                3. bangumi_characters：查条目的登场角色（主役/配角）。问「主要人物」「宿舍伙伴是谁」必用它；追问时 keyword 用对话历史里的作品名。
                4. bangumi_person_works：查作者/漫画家及其全部作品。问「某作者有哪些漫画」「某作品作者还画过什么」必用它。
                5. 涉及 Bangumi 能回答的事实（评分/季数/作者/作品列表/角色）时，禁止凭记忆 final，必须先调 2、3 或 4。
                6. 统计季数时，以 Observation 中「共找到 N 部相关动画条目」为准，不要只凭第一条条目推断。
                7. 只有用户明确问「站内有没有写过…」时才优先 fulltext_search / article_rag。
                8. 若有对话历史，追问需结合上文主题选 keyword（如上文谈某作品，追问「宿舍伙伴」应调 bangumi_characters）。
                9. 获得足够 Observation 后再 action=final；不要编造工具未返回的内容。""");
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
