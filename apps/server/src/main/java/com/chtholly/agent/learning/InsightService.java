package com.chtholly.agent.learning;

import com.chtholly.agent.memory.AgentTurn;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reflects on conversations and manages two-level learned behavior insights.
 *
 * <p>Personal insights are isolated per user with a hard cap. Global insights
 * are promoted only after the same semantic rule appears from multiple users.
 */
@Slf4j
@Service
public class InsightService {

    private static final String PERSONAL_KEY_PREFIX = "insights:personal:";
    private static final String GLOBAL_KEY = "insights:global";
    private static final String GLOBAL_META_KEY_PREFIX = "insights:global:meta:";
    private static final String GLOBAL_CANDIDATE_KEY = "insights:global:candidate";

    private static final int MIN_TURNS_FOR_REFLECTION = 6;
    private static final int MAX_NEW_RULES_PER_REFLECTION = 3;
    private static final int EXISTING_RULE_LIMIT = 15;
    private static final int EXISTING_RULE_CHARS = 2_000;
    private static final int MAX_PERSONAL_INSIGHTS = 15;
    private static final int GLOBAL_PROMOTION_USER_THRESHOLD = 5;

    private static final double INITIAL_PERSONAL_CONFIDENCE = 0.5;
    private static final double GLOBAL_PROMOTED_CONFIDENCE = 0.85;

    private final ObjectMapper objectMapper;
    private final Function<String, List<String>> insightGenerator;
    private final Clock clock;
    private final StringRedisTemplate redis;

    @Autowired
    public InsightService(ObjectMapper objectMapper,
                          ObjectProvider<ChatClient> chatClientProvider,
                          StringRedisTemplate redis) {
        this(objectMapper,
                prompt -> generateWithChatClient(chatClientProvider.getIfAvailable(), objectMapper, prompt),
                Clock.systemUTC(),
                redis);
    }

    InsightService(ObjectMapper objectMapper,
                   Function<String, List<String>> insightGenerator,
                   Clock clock,
                   StringRedisTemplate redis) {
        this.objectMapper = objectMapper;
        this.insightGenerator = insightGenerator;
        this.clock = clock;
        this.redis = redis;
    }

    /**
     * Candidate waiting for global promotion.
     *
     * @param id           Stable insight ID.
     * @param text         Rule text.
     * @param userId       Source user ID.
     * @param semanticHash Normalized semantic bucket.
     * @param createdAt    Candidate creation time.
     */
    public record GlobalInsightCandidate(
            String id,
            String text,
            long userId,
            String semanticHash,
            Instant createdAt
    ) {
    }

    /**
     * Prompt-ready insight merged from global and personal stores.
     *
     * @param id              Insight ID.
     * @param text            Rule text.
     * @param confidenceScore Confidence score.
     * @param source          {@code global} or {@code personal}.
     */
    public record UserInsight(
            String id,
            String text,
            double confidenceScore,
            String source
    ) {
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

        String existingRules = String.join("\n", getInsightTextsForUser(userId, EXISTING_RULE_LIMIT, EXISTING_RULE_CHARS));
        String reflectPrompt = buildReflectPrompt(conversation, existingRules);
        List<String> newRules = insightGenerator.apply(reflectPrompt);
        if (newRules == null || newRules.isEmpty()) {
            return;
        }

        newRules.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .limit(MAX_NEW_RULES_PER_REFLECTION)
                .forEach(rule -> storePersonalInsight(userId, rule));
    }

    /**
     * Stores a user-scoped insight and registers it as a global candidate.
     *
     * @param userId Authenticated user ID.
     * @param text   Insight text.
     */
    public void storePersonalInsight(long userId, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        String normalizedText = text.trim();
        Instant now = Instant.now(clock);
        Insight insight = new Insight(
                generateId(normalizedText),
                normalizedText,
                now,
                now,
                0,
                INITIAL_PERSONAL_CONFIDENCE,
                Insight.InsightState.ACTIVE);

        saveInsight(personalKey(userId), insight);
        saveGlobalCandidate(new GlobalInsightCandidate(
                insight.id(),
                normalizedText,
                userId,
                semanticHash(normalizedText),
                now));
        enforcePersonalLimit(userId);
    }

    /**
     * Returns merged global and personal insights for a user.
     *
     * @param userId Authenticated user ID.
     * @return Prompt-ready insights sorted by confidence.
     */
    public List<UserInsight> getInsightsForUser(long userId) {
        List<Insight> globals = active(loadInsights(GLOBAL_KEY));
        Set<String> globalHashes = globals.stream()
                .map(insight -> semanticHash(insight.text()))
                .collect(Collectors.toSet());

        List<UserInsight> merged = new ArrayList<>();
        for (Insight insight : globals) {
            merged.add(new UserInsight(insight.id(), insight.text(), insight.confidenceScore(), "global"));
        }
        for (Insight insight : active(loadInsights(personalKey(userId)))) {
            if (!globalHashes.contains(semanticHash(insight.text()))) {
                merged.add(new UserInsight(insight.id(), insight.text(), insight.confidenceScore(), "personal"));
            }
        }

        merged.sort(Comparator.comparingDouble(UserInsight::confidenceScore).reversed()
                .thenComparing(UserInsight::source)
                .thenComparing(UserInsight::text));
        return List.copyOf(merged);
    }

    /**
     * Returns prompt-ready insight texts with count and character limits.
     *
     * @param userId   Authenticated user ID.
     * @param topN     Maximum number of insights.
     * @param maxChars Maximum total characters.
     * @return Insight texts for prompt injection.
     */
    public List<String> getInsightTextsForUser(long userId, int topN, int maxChars) {
        if (topN <= 0 || maxChars <= 0) {
            return List.of();
        }

        List<String> texts = new ArrayList<>();
        int totalChars = 0;
        for (UserInsight insight : getInsightsForUser(userId)) {
            if (texts.size() >= topN) {
                break;
            }
            String text = insight.text().trim();
            if (totalChars + text.length() > maxChars) {
                break;
            }
            texts.add(text);
            totalChars += text.length();
        }
        return List.copyOf(texts);
    }

    /** Compatibility entry point for procedural-memory writers. */
    public void storeRule(long userId, String ruleText) {
        storePersonalInsight(userId, ruleText);
    }

    /** Returns prompt-ready procedural rules from the canonical two-level insight store. */
    public List<String> getTopRules(long userId, int topN, int maxChars) {
        return getInsightTextsForUser(userId, topN, maxChars);
    }

    /** Records successful use of a personal or global rule. */
    public void recordRuleUsage(long userId, String ruleId) {
        updateRule(userId, ruleId, insight -> new Insight(
                insight.id(), insight.text(), insight.createdAt(), Instant.now(clock),
                insight.useCount() + 1,
                Math.min(1.0, insight.confidenceScore() + 0.1), insight.state()));
    }

    /** Lowers rule confidence and retires rules that repeatedly receive negative feedback. */
    public void recordNegativeFeedback(long userId, String ruleId) {
        updateRule(userId, ruleId, insight -> {
            double confidence = Math.max(0.0, insight.confidenceScore() - 0.2);
            Insight.InsightState state = confidence < 0.2
                    ? Insight.InsightState.STALE
                    : insight.state();
            return new Insight(insight.id(), insight.text(), insight.createdAt(), insight.lastUsedAt(),
                    insight.useCount(), confidence, state);
        });
    }

    private void updateRule(long userId, String ruleId, java.util.function.UnaryOperator<Insight> updater) {
        if (!StringUtils.hasText(ruleId)) return;
        for (String key : List.of(personalKey(userId), GLOBAL_KEY)) {
            Object raw = redis.opsForHash().get(key, ruleId);
            if (raw == null) continue;
            try {
                Insight insight = objectMapper.readValue(raw.toString(), Insight.class);
                saveInsight(key, updater.apply(insight));
                return;
            } catch (Exception e) {
                log.warn("Insight usage update failed key={}, ruleId={}", key, ruleId, e);
                return;
            }
        }
    }

    /**
     * Curates candidates and promotes rules shared by at least five users.
     */
    @Scheduled(cron = "0 0 */6 * * *")
    public void curateInsights() {
        Set<String> rawCandidates = redis.opsForZSet()
                .rangeByScore(GLOBAL_CANDIDATE_KEY, 0, Double.MAX_VALUE);
        if (rawCandidates == null || rawCandidates.isEmpty()) {
            return;
        }

        Map<String, List<CandidateEntry>> grouped = rawCandidates.stream()
                .map(this::parseCandidate)
                .flatMap(List::stream)
                .collect(Collectors.groupingBy(entry -> entry.candidate().semanticHash()));

        for (List<CandidateEntry> group : grouped.values()) {
            Set<Long> userIds = group.stream()
                    .map(entry -> entry.candidate().userId())
                    .collect(Collectors.toCollection(HashSet::new));
            if (userIds.size() < GLOBAL_PROMOTION_USER_THRESHOLD) {
                continue;
            }
            promoteGlobalInsight(group, userIds);
        }
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
                - 和已有 insight 重复的内容（已有列表：%s）

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

    private void promoteGlobalInsight(List<CandidateEntry> group, Set<Long> userIds) {
        String mergedText = group.stream()
                .map(entry -> entry.candidate().text())
                .filter(StringUtils::hasText)
                .min(Comparator.comparingInt(String::length))
                .orElse(group.getFirst().candidate().text())
                .trim();
        Instant now = Instant.now(clock);
        Insight global = new Insight(
                generateId(mergedText),
                mergedText,
                now,
                now,
                0,
                GLOBAL_PROMOTED_CONFIDENCE,
                Insight.InsightState.ACTIVE);

        saveInsight(GLOBAL_KEY, global);
        saveGlobalMeta(global.id(), userIds);

        String semanticHash = semanticHash(mergedText);
        for (Long userId : userIds) {
            removePersonalBySemanticHash(userId, semanticHash);
        }
        redis.opsForZSet().remove(GLOBAL_CANDIDATE_KEY, group.stream()
                .map(CandidateEntry::raw)
                .toArray(String[]::new));
    }

    private void enforcePersonalLimit(long userId) {
        String key = personalKey(userId);
        Set<String> globalHashes = active(loadInsights(GLOBAL_KEY)).stream()
                .map(insight -> semanticHash(insight.text()))
                .collect(Collectors.toSet());

        for (Insight insight : loadInsights(key)) {
            if (globalHashes.contains(semanticHash(insight.text()))) {
                redis.opsForHash().delete(key, insight.id());
            }
        }

        List<Insight> insights = new ArrayList<>(loadInsights(key).stream()
                .filter(insight -> insight.state() == Insight.InsightState.ACTIVE)
                .toList());
        if (insights.size() <= MAX_PERSONAL_INSIGHTS) {
            return;
        }

        insights.sort(Comparator.comparingDouble(this::personalCompositeScore));
        int removeCount = insights.size() - MAX_PERSONAL_INSIGHTS;
        for (int i = 0; i < removeCount; i++) {
            redis.opsForHash().delete(key, insights.get(i).id());
        }
    }

    private double personalCompositeScore(Insight insight) {
        Instant lastHit = insight.lastUsedAt() == null ? insight.createdAt() : insight.lastUsedAt();
        double daysSinceLastHit = Math.max(0.0, Duration.between(lastHit, Instant.now(clock)).toHours() / 24.0);
        double recencyNorm = Math.max(0.0, Math.min(1.0, 1.0 - (daysSinceLastHit / 30.0)));
        return insight.confidenceScore() * 0.6 + recencyNorm * 0.4;
    }

    private void removePersonalBySemanticHash(long userId, String semanticHash) {
        String key = personalKey(userId);
        for (Insight insight : loadInsights(key)) {
            if (semanticHash.equals(semanticHash(insight.text()))) {
                redis.opsForHash().delete(key, insight.id());
            }
        }
    }

    private void saveInsight(String key, Insight insight) {
        try {
            redis.opsForHash().put(key, insight.id(), objectMapper.writeValueAsString(insight));
        } catch (Exception e) {
            log.warn("Insight save failed key={}, id={}: {}", key, insight.id(), e.getMessage());
        }
    }

    private void saveGlobalCandidate(GlobalInsightCandidate candidate) {
        try {
            redis.opsForZSet().add(
                    GLOBAL_CANDIDATE_KEY,
                    objectMapper.writeValueAsString(candidate),
                    candidate.createdAt().toEpochMilli());
        } catch (Exception e) {
            log.warn("Insight candidate save failed userId={}, id={}: {}", candidate.userId(), candidate.id(), e.getMessage());
        }
    }

    private void saveGlobalMeta(String insightId, Set<Long> userIds) {
        try {
            List<Long> sorted = userIds.stream().sorted().toList();
            String key = GLOBAL_META_KEY_PREFIX + insightId;
            redis.opsForHash().put(key, "sharedBy", objectMapper.writeValueAsString(sorted));
            redis.opsForHash().put(key, "count", String.valueOf(sorted.size()));
        } catch (Exception e) {
            log.warn("Global insight meta save failed id={}: {}", insightId, e.getMessage());
        }
    }

    private List<Insight> loadInsights(String key) {
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<Insight> insights = new ArrayList<>();
        for (Object value : entries.values()) {
            if (value == null || !StringUtils.hasText(value.toString())) {
                continue;
            }
            try {
                insights.add(objectMapper.readValue(value.toString(), Insight.class));
            } catch (Exception e) {
                log.warn("Insight deserialize failed key={}: {}", key, e.getMessage());
            }
        }
        return insights;
    }

    private List<Insight> active(List<Insight> insights) {
        return insights.stream()
                .filter(insight -> insight.state() == Insight.InsightState.ACTIVE)
                .filter(insight -> StringUtils.hasText(insight.text()))
                .toList();
    }

    private List<CandidateEntry> parseCandidate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return List.of(new CandidateEntry(objectMapper.readValue(raw, GlobalInsightCandidate.class), raw));
        } catch (Exception e) {
            log.warn("Global insight candidate deserialize failed: {}", e.getMessage());
            return List.of();
        }
    }

    private record CandidateEntry(GlobalInsightCandidate candidate, String raw) {
    }

    private static String personalKey(long userId) {
        return PERSONAL_KEY_PREFIX + userId;
    }

    static String semanticHash(String text) {
        return normalize(text).toLowerCase();
    }

    private static String generateId(String text) {
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

    private static String normalize(String text) {
        return text == null
                ? ""
                : text.trim()
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "")
                .toLowerCase();
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
