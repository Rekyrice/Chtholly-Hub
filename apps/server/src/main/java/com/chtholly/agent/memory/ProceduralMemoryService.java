package com.chtholly.agent.memory;

import com.chtholly.agent.learning.Insight;
import com.chtholly.agent.learning.Insight.InsightState;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Stores and manages procedural memory rules for agent behavior.
 *
 * <p>Procedural memory is long-lived "how to respond" knowledge, stored as
 * structured {@link Insight} records in a Redis Hash at {@code agent:procedural:{userId}}.
 */
@Slf4j
@Service
public class ProceduralMemoryService {

    private static final String KEY_PREFIX = "agent:procedural:";
    private static final double INITIAL_CONFIDENCE = 0.5;
    private static final double USAGE_CONFIDENCE_STEP = 0.1;
    private static final double NEGATIVE_CONFIDENCE_STEP = 0.2;
    private static final double STALE_CONFIDENCE_THRESHOLD = 0.2;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ProceduralMemoryService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this(redis, objectMapper, Clock.systemUTC());
    }

    ProceduralMemoryService(StringRedisTemplate redis, ObjectMapper objectMapper, Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Stores a new procedural rule as an active insight.
     *
     * @param userId   Authenticated user ID.
     * @param ruleText Behavior rule text.
     */
    public void storeRule(long userId, String ruleText) {
        if (!StringUtils.hasText(ruleText)) {
            return;
        }
        String text = ruleText.trim();
        Instant now = Instant.now(clock);
        saveInsight(userId, new Insight(
                generateId(text),
                text,
                now,
                now,
                0,
                INITIAL_CONFIDENCE,
                InsightState.ACTIVE));
    }

    /**
     * Retrieves top active rules for prompt injection.
     *
     * @param userId   Authenticated user ID.
     * @param topN     Maximum number of rules.
     * @param maxChars Maximum total characters.
     * @return Active rule texts sorted by use count descending.
     */
    public List<String> getTopRules(long userId, int topN, int maxChars) {
        if (topN <= 0 || maxChars <= 0) {
            return List.of();
        }
        List<Insight> active = getActiveInsights(userId);
        active.sort(Comparator.comparingInt(Insight::useCount).reversed()
                .thenComparing(Comparator.comparingDouble(Insight::confidenceScore).reversed())
                .thenComparing(Insight::createdAt));

        List<String> rules = new ArrayList<>();
        int totalChars = 0;
        for (Insight insight : active) {
            if (rules.size() >= topN) {
                break;
            }
            String text = insight.text().trim();
            if (totalChars + text.length() > maxChars) {
                break;
            }
            rules.add(text);
            totalChars += text.length();
        }
        return List.copyOf(rules);
    }

    /**
     * Records that a procedural rule was used.
     *
     * @param userId Authenticated user ID.
     * @param ruleId Rule ID.
     */
    public void recordRuleUsage(long userId, String ruleId) {
        Insight insight = loadInsight(userId, ruleId);
        if (insight == null) {
            return;
        }

        saveInsight(userId, new Insight(
                insight.id(),
                insight.text(),
                insight.createdAt(),
                Instant.now(clock),
                insight.useCount() + 1,
                Math.min(1.0, insight.confidenceScore() + USAGE_CONFIDENCE_STEP),
                insight.state()));
    }

    /**
     * Records negative feedback for a procedural rule.
     *
     * @param userId Authenticated user ID.
     * @param ruleId Rule ID.
     */
    public void recordNegativeFeedback(long userId, String ruleId) {
        Insight insight = loadInsight(userId, ruleId);
        if (insight == null) {
            return;
        }

        double confidence = Math.max(0.0, insight.confidenceScore() - NEGATIVE_CONFIDENCE_STEP);
        InsightState state = confidence < STALE_CONFIDENCE_THRESHOLD ? InsightState.STALE : insight.state();
        saveInsight(userId, new Insight(
                insight.id(),
                insight.text(),
                insight.createdAt(),
                insight.lastUsedAt(),
                insight.useCount(),
                confidence,
                state));
    }

    private List<Insight> getActiveInsights(long userId) {
        return new ArrayList<>(loadInsights(userId).stream()
                .filter(insight -> insight.state() == InsightState.ACTIVE)
                .filter(insight -> StringUtils.hasText(insight.text()))
                .toList());
    }

    private List<Insight> loadInsights(long userId) {
        Map<Object, Object> entries = redis.opsForHash().entries(key(userId));
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
                log.warn("Procedural memory deserialize failed userId={}: {}", userId, e.getMessage());
            }
        }
        return insights;
    }

    private Insight loadInsight(long userId, String ruleId) {
        if (!StringUtils.hasText(ruleId)) {
            return null;
        }
        Object value = redis.opsForHash().get(key(userId), ruleId);
        if (value == null || !StringUtils.hasText(value.toString())) {
            return null;
        }
        try {
            return objectMapper.readValue(value.toString(), Insight.class);
        } catch (Exception e) {
            log.warn("Procedural memory load failed userId={}, ruleId={}: {}", userId, ruleId, e.getMessage());
            return null;
        }
    }

    private void saveInsight(long userId, Insight insight) {
        try {
            redis.opsForHash().put(key(userId), insight.id(), objectMapper.writeValueAsString(insight));
        } catch (Exception e) {
            log.warn("Procedural memory save failed userId={}, ruleId={}: {}", userId, insight.id(), e.getMessage());
        }
    }

    private static String key(long userId) {
        return KEY_PREFIX + userId;
    }

    private static String generateId(String ruleText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalize(ruleText).getBytes(StandardCharsets.UTF_8));
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
        return text == null ? "" : text.trim().replaceAll("\\s+", "");
    }
}
