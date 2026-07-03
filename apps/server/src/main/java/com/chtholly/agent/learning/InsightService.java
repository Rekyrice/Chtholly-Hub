package com.chtholly.agent.learning;

import com.chtholly.agent.learning.Insight.InsightState;
import com.chtholly.agent.memory.AgentTurn;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Stores and curates learned behavior rules for Chtholly's agent responses.
 *
 * <p>Insights are persisted as a Redis Hash at {@code agent:insights:{userId}},
 * with field {@code insightId} and value {@code Insight JSON}.
 */
@Slf4j
@Service
public class InsightService {

    private static final String KEY_PREFIX = "agent:insights:";
    private static final int MAX_ACTIVE_PER_USER = 15;
    private static final int TOP_N_FOR_PROMPT = 5;
    private static final int MAX_CHARS_FOR_PROMPT = 500;
    private static final double DEFAULT_CONFIDENCE = 0.6;
    private static final Duration STALE_AFTER = Duration.ofDays(30);
    private static final Duration ARCHIVE_AFTER = Duration.ofDays(90);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Function<String, List<String>> insightGenerator;
    private final Clock clock;

    public InsightService(StringRedisTemplate redis,
                          ObjectMapper objectMapper,
                          ObjectProvider<ChatClient> chatClientProvider) {
        this(redis, objectMapper, prompt -> generateWithChatClient(chatClientProvider.getIfAvailable(), objectMapper, prompt),
                Clock.systemUTC());
    }

    InsightService(StringRedisTemplate redis,
                   ObjectMapper objectMapper,
                   Function<String, List<String>> insightGenerator,
                   Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.insightGenerator = insightGenerator;
        this.clock = clock;
    }

    /**
     * Extracts insights from a completed conversation.
     *
     * @param userId       Authenticated user ID.
     * @param conversation Completed conversation turns.
     */
    @Async("agentExecutor")
    public void reflectOnConversation(long userId, List<AgentTurn> conversation) {
        if (conversation == null || conversation.size() < 6) {
            return;
        }

        String existingInsights = getActiveInsightTexts(userId);
        String reflectPrompt = buildReflectPrompt(conversation, existingInsights);
        List<String> newInsights = insightGenerator.apply(reflectPrompt);
        if (newInsights == null || newInsights.isEmpty()) {
            return;
        }

        for (String text : newInsights.stream().filter(StringUtils::hasText).limit(3).toList()) {
            Insight insight = createInsight(text.trim());
            if (!isDuplicate(userId, insight)) {
                saveInsight(userId, insight);
            }
        }

        if (countActive(userId) > MAX_ACTIVE_PER_USER) {
            curatorConsolidation(userId);
        }
    }

    /**
     * Returns top active insight texts for prompt injection.
     *
     * @param userId Authenticated user ID.
     * @param topN   Maximum number of insights.
     * @return Active insight texts ordered by use count.
     */
    public List<String> getActiveInsights(long userId, int topN) {
        int limit = Math.min(Math.max(1, topN), TOP_N_FOR_PROMPT);
        List<Insight> active = loadInsights(userId).stream()
                .filter(insight -> insight.state() == InsightState.ACTIVE)
                .sorted(promptOrder())
                .toList();

        List<String> result = new ArrayList<>();
        int chars = 0;
        for (Insight insight : active) {
            if (result.size() >= limit) {
                break;
            }
            String text = insight.text().trim();
            if (chars + text.length() > MAX_CHARS_FOR_PROMPT) {
                break;
            }
            result.add(text);
            chars += text.length();
            saveInsight(userId, new Insight(
                    insight.id(),
                    insight.text(),
                    insight.createdAt(),
                    Instant.now(clock),
                    insight.useCount() + 1,
                    insight.confidenceScore(),
                    insight.state()));
        }
        return result;
    }

    /**
     * Lifecycle management for stale and archived insights.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void lifecycleManagement() {
        Set<String> keys = redis.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        for (String key : keys) {
            for (Insight insight : loadInsightsByKey(key)) {
                Insight next = lifecycleState(insight, now);
                if (next != insight) {
                    saveInsightByKey(key, next);
                }
            }
        }
    }

    private Insight lifecycleState(Insight insight, Instant now) {
        if (insight.state() == InsightState.ARCHIVED) {
            return insight;
        }
        if (insight.lastUsedAt().isBefore(now.minus(ARCHIVE_AFTER))) {
            return withState(insight, InsightState.ARCHIVED);
        }
        if (insight.lastUsedAt().isBefore(now.minus(STALE_AFTER)) || insight.confidenceScore() < 0.2) {
            return withState(insight, InsightState.STALE);
        }
        return insight;
    }

    private void curatorConsolidation(long userId) {
        List<Insight> active = loadInsights(userId).stream()
                .filter(insight -> insight.state() == InsightState.ACTIVE)
                .sorted(promptOrder())
                .toList();
        if (active.size() <= MAX_ACTIVE_PER_USER) {
            return;
        }

        for (Insight insight : active.subList(MAX_ACTIVE_PER_USER, active.size())) {
            saveInsight(userId, withState(insight, InsightState.STALE));
        }
    }

    private String buildReflectPrompt(List<AgentTurn> conversation, String existingInsights) {
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
                - 和已有 insight 重复的内容（已有列表：%s）

                输出格式：JSON 数组，每项一句话。如果没有新洞察，返回空数组 []。

                对话内容：
                %s
                """.formatted(existingInsights, formatConversation(conversation));
    }

    private String getActiveInsightTexts(long userId) {
        return String.join("\n", loadInsights(userId).stream()
                .filter(insight -> insight.state() == InsightState.ACTIVE)
                .sorted(promptOrder())
                .map(Insight::text)
                .toList());
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

    private Insight createInsight(String text) {
        Instant now = Instant.now(clock);
        return new Insight(hashId(text), text, now, now, 0, DEFAULT_CONFIDENCE, InsightState.ACTIVE);
    }

    private boolean isDuplicate(long userId, Insight candidate) {
        String normalized = normalize(candidate.text());
        return loadInsights(userId).stream()
                .anyMatch(existing -> normalize(existing.text()).equals(normalized));
    }

    private void saveInsight(long userId, Insight insight) {
        saveInsightByKey(key(userId), insight);
    }

    private void saveInsightByKey(String key, Insight insight) {
        try {
            redis.opsForHash().put(key, insight.id(), objectMapper.writeValueAsString(insight));
        } catch (Exception e) {
            log.warn("Agent insight save failed key={}, id={}: {}", key, insight.id(), e.getMessage());
        }
    }

    private int countActive(long userId) {
        return (int) loadInsights(userId).stream()
                .filter(insight -> insight.state() == InsightState.ACTIVE)
                .count();
    }

    private List<Insight> loadInsights(long userId) {
        return loadInsightsByKey(key(userId));
    }

    private List<Insight> loadInsightsByKey(String key) {
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        List<Insight> insights = new ArrayList<>(entries.size());
        for (Object value : entries.values()) {
            if (value == null || !StringUtils.hasText(value.toString())) {
                continue;
            }
            try {
                insights.add(objectMapper.readValue(value.toString(), Insight.class));
            } catch (Exception e) {
                log.warn("Agent insight deserialize failed key={}: {}", key, e.getMessage());
            }
        }
        return insights;
    }

    private static Comparator<Insight> promptOrder() {
        return Comparator.comparingInt(Insight::useCount).reversed()
                .thenComparing(Comparator.comparingDouble(Insight::confidenceScore).reversed())
                .thenComparing(Insight::createdAt);
    }

    private static Insight withState(Insight insight, InsightState state) {
        if (insight.state() == state) {
            return insight;
        }
        return new Insight(
                insight.id(),
                insight.text(),
                insight.createdAt(),
                insight.lastUsedAt(),
                insight.useCount(),
                insight.confidenceScore(),
                state);
    }

    private static String key(long userId) {
        return KEY_PREFIX + userId;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", "");
    }

    private static String hashId(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalize(text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append("%02x".formatted(bytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
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
