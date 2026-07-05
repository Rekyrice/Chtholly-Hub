package com.chtholly.agent.learning;

import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.memory.ProceduralMemoryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.List;
import java.util.function.Function;

/**
 * Reflects on conversations and extracts procedural behavior rules.
 *
 * <p>This service is responsible for learning candidate rules from completed
 * conversations. Storage, retrieval, feedback, and lifecycle are owned by
 * {@link ProceduralMemoryService}.
 */
@Slf4j
@Service
public class InsightService {

    private static final int MIN_TURNS_FOR_REFLECTION = 6;
    private static final int MAX_NEW_RULES_PER_REFLECTION = 3;
    private static final int EXISTING_RULE_LIMIT = 15;
    private static final int EXISTING_RULE_CHARS = 2_000;

    private final ObjectMapper objectMapper;
    private final Function<String, List<String>> insightGenerator;
    @SuppressWarnings("unused")
    private final Clock clock;
    private final ProceduralMemoryService proceduralMemoryService;

    @Autowired
    public InsightService(ObjectMapper objectMapper,
                          ObjectProvider<ChatClient> chatClientProvider,
                          ProceduralMemoryService proceduralMemoryService) {
        this(objectMapper,
                prompt -> generateWithChatClient(chatClientProvider.getIfAvailable(), objectMapper, prompt),
                Clock.systemUTC(),
                proceduralMemoryService);
    }

    InsightService(ObjectMapper objectMapper,
                   Function<String, List<String>> insightGenerator,
                   Clock clock,
                   ProceduralMemoryService proceduralMemoryService) {
        this.objectMapper = objectMapper;
        this.insightGenerator = insightGenerator;
        this.clock = clock;
        this.proceduralMemoryService = proceduralMemoryService;
    }

    /**
     * Extracts procedural rules from a completed conversation.
     *
     * @param userId       Authenticated user ID.
     * @param conversation Completed conversation turns.
     */
    @Async("agentExecutor")
    public void reflectOnConversation(long userId, List<AgentTurn> conversation) {
        if (conversation == null || conversation.size() < MIN_TURNS_FOR_REFLECTION) {
            return;
        }

        String existingRules = String.join("\n",
                proceduralMemoryService.getTopRules(userId, EXISTING_RULE_LIMIT, EXISTING_RULE_CHARS));
        String reflectPrompt = buildReflectPrompt(conversation, existingRules);
        List<String> newRules = insightGenerator.apply(reflectPrompt);
        if (newRules == null || newRules.isEmpty()) {
            return;
        }

        newRules.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .limit(MAX_NEW_RULES_PER_REFLECTION)
                .forEach(rule -> proceduralMemoryService.storeRule(userId, rule));
    }

    private String buildReflectPrompt(List<AgentTurn> conversation, String existingRules) {
        return """
                回顾这次对话，提取 2-3 条关于你（Agent）行为的改进建议。
                不是用户偏好，是你自己的行为规则。

                好的洞察示例：
                - "回答角色类问题时，先列主要角色再补充声优信息效果更好"
                - "用户问评分时同时提供集数和放送日期比只给评分更有用"
                - "对于冷门番查询，先确认作品名再搜索可避免搜错"

                不要提取：
                - 用户喜欢什么番（那是记忆，不是行为改进）
                - 通用的礼貌用语建议
                - 和已有规则重复的内容（已有列表：%s）

                输出格式：JSON 数组，每项一句话。如果没有新洞察，返回空数组 []。

                对话内容：
                %s
                """.formatted(existingRules == null ? "" : existingRules, formatConversation(conversation));
    }

    private String formatConversation(List<AgentTurn> conversation) {
        StringBuilder sb = new StringBuilder();
        for (AgentTurn turn : conversation) {
            if (turn == null || !StringUtils.hasText(turn.content())) {
                continue;
            }
            sb.append(turn.role() == AgentTurn.Role.USER ? "User: " : "Assistant: ")
                    .append(turn.content().trim())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    private static List<String> generateWithChatClient(ChatClient chatClient, ObjectMapper objectMapper, String prompt) {
        if (chatClient == null || !StringUtils.hasText(prompt)) {
            return List.of();
        }
        try {
            String output = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            if (!StringUtils.hasText(output)) {
                return List.of();
            }
            return objectMapper.readValue(output, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("Agent insight LLM reflection failed: {}", e.getMessage());
            return List.of();
        }
    }
}
