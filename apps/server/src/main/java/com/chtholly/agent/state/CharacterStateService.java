package com.chtholly.agent.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages the living Character State for each user.
 *
 * <p>This is the "alive data" that evolves through interactions:
 * mood, relationship, and behavior probabilities.
 * Stored as a Redis Hash with 30-day sliding TTL.
 */
@Service
public class CharacterStateService {

    private static final String KEY_PREFIX = "agent:character-state:";
    private static final String MOOD_KEY = "character:state:mood";
    private static final String EMOTION_KEY = "character:state:emotion";
    private static final Duration TTL = Duration.ofDays(30);
    private static final Duration EMOTION_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Creates a Redis-backed character state service.
     *
     * @param redis        Redis template used for hash persistence.
     * @param objectMapper Shared mapper reserved for future nested state serialization.
     */
    @Autowired
    public CharacterStateService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this(redis, objectMapper, Clock.systemDefaultZone());
    }

    CharacterStateService(StringRedisTemplate redis, ObjectMapper objectMapper, Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Loads or initializes character state for a user.
     *
     * @param userId User ID.
     * @return Existing or default state.
     */
    public CharacterState load(long userId) {
        return load(userId, true);
    }

    private CharacterState load(long userId, boolean refreshTtl) {
        String key = key(userId);
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return createDefault(userId);
        }
        if (refreshTtl) {
            redis.expire(key, TTL);
        }
        return deserialize(entries);
    }

    /**
     * Persists character state and refreshes its sliding TTL.
     *
     * @param userId User ID.
     * @param state  State to persist.
     */
    public void save(long userId, CharacterState state) {
        String key = key(userId);
        redis.opsForHash().putAll(key, serialize(state));
        redis.expire(key, TTL);
    }

    /**
     * Updates relationship state after a conversation turn.
     *
     * @param userId User ID.
     */
    public void recordInteraction(long userId) {
        CharacterState state = load(userId, false);
        long nextCount = state.relationship().interactionCount() + 1;
        double intimacy = Math.min(1.0, 0.1 * Math.log(1 + nextCount));
        CharacterState updated = new CharacterState(
                state.personality(),
                state.mood(),
                new Relationship(intimacy, nextCount, Instant.now()),
                state.needs(),
                state.behaviorProb());
        save(userId, updated);
    }

    /**
     * Get mood baseline based on time of day.
     *
     * @return Mood baseline for current local time.
     */
    public double getMoodBaseline() {
        int hour = LocalTime.now(clock).getHour();
        if (hour >= 6 && hour < 9) {
            return 0.2;
        }
        if (hour >= 9 && hour < 12) {
            return 0.1;
        }
        if (hour >= 12 && hour < 18) {
            return 0.0;
        }
        if (hour >= 18 && hour < 21) {
            return 0.1;
        }
        if (hour >= 21 || hour < 1) {
            return -0.1;
        }
        return -0.3;
    }

    /**
     * Persists global slow-layer mood valence.
     *
     * @param valence Mood valence in [-1.0, 1.0].
     */
    public void updateMoodValence(double valence) {
        redis.opsForHash().put(MOOD_KEY, "valence", Double.toString(clamp(valence, -1.0, 1.0)));
    }

    /**
     * Reads global slow-layer mood valence.
     *
     * @return Stored valence, or neutral 0.0 when absent.
     */
    public double getMoodValence() {
        Object value = redis.opsForHash().get(MOOD_KEY, "valence");
        if (value == null) {
            return 0.0;
        }
        try {
            return clamp(Double.parseDouble(value.toString()), -1.0, 1.0);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Returns active per-user relationship intimacy scores.
     *
     * @return intimacy scores found in Redis state hashes.
     */
    public List<Double> getActiveUserIntimacies() {
        Set<String> keys = redis.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<Double> intimacies = new ArrayList<>();
        for (String stateKey : keys) {
            Object value = redis.opsForHash().get(stateKey, "relationship.intimacy");
            if (value == null) {
                continue;
            }
            try {
                intimacies.add(clamp(Double.parseDouble(value.toString()), 0.0, 1.0));
            } catch (NumberFormatException e) {
                // Redis 中可能存在历史脏值，跳过即可，不让情绪循环失败。
            }
        }
        return List.copyOf(intimacies);
    }

    /**
     * 返回 Redis 中已有 CharacterState 的用户 ID。
     */
    public List<Long> listKnownUserIds() {
        Set<String> keys = redis.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = new ArrayList<>();
        for (String stateKey : keys) {
            String suffix = stateKey.substring(KEY_PREFIX.length());
            try {
                userIds.add(Long.parseLong(suffix));
            } catch (NumberFormatException ignored) {
                // skip malformed key
            }
        }
        return List.copyOf(userIds);
    }

    /**
     * 上次互动早于阈值的活跃用户（用于缺席/回归检测）。
     */
    public List<Long> findUserIdsLastSeenBefore(Instant threshold) {
        if (threshold == null) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Long userId : listKnownUserIds()) {
            Instant lastSeen = load(userId, false).relationship().lastSeen();
            if (lastSeen.isBefore(threshold)) {
                result.add(userId);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 在指定时间之后仍活跃的用户。
     */
    public List<Long> findUserIdsActiveSince(Instant since) {
        if (since == null) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Long userId : listKnownUserIds()) {
            Instant lastSeen = load(userId, false).relationship().lastSeen();
            if (!lastSeen.isBefore(since)) {
                result.add(userId);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Updates fast-layer emotion from a user interaction.
     *
     * @param userId         User ID that triggered the interaction.
     * @param messageContent User message content.
     */
    public void updateEmotion(Long userId, String messageContent) {
        EmotionState emotion = new EmotionState(
                detectEmotion(messageContent),
                calculateIntensity(messageContent),
                clock.instant(),
                "user-interaction");
        redis.opsForHash().putAll(EMOTION_KEY, serializeEmotion(emotion));
        redis.expire(EMOTION_KEY, EMOTION_TTL);
    }

    /**
     * Returns current emotion after natural 30-minute decay.
     *
     * @return current fast-layer emotion.
     */
    public EmotionState getCurrentEmotion() {
        Map<Object, Object> entries = redis.opsForHash().entries(EMOTION_KEY);
        if (entries == null || entries.isEmpty()) {
            return defaultEmotion();
        }
        EmotionState emotion = deserializeEmotion(entries);
        long elapsedSeconds = Math.max(0, Duration.between(emotion.triggeredAt(), clock.instant()).toSeconds());
        double decay = Math.max(0.0, 1.0 - elapsedSeconds / (double) EMOTION_TTL.toSeconds());
        double decayedIntensity = clamp(emotion.intensity() * decay, 0.0, 1.0);
        if (decayedIntensity < 0.1) {
            return defaultEmotion();
        }
        return new EmotionState(emotion.label(), decayedIntensity, emotion.triggeredAt(), emotion.trigger());
    }

    private CharacterState createDefault(long userId) {
        CharacterState state = CharacterState.defaultState(Instant.now());
        save(userId, state);
        return state;
    }

    private Map<String, String> serialize(CharacterState state) {
        Map<String, String> entries = new LinkedHashMap<>();
        put(entries, "personality.warmth", state.personality().warmth());
        put(entries, "personality.curiosity", state.personality().curiosity());
        put(entries, "personality.playfulness", state.personality().playfulness());
        put(entries, "mood.valence", state.mood().valence());
        put(entries, "mood.arousal", state.mood().arousal());
        put(entries, "mood.baseline", state.mood().baseline());
        put(entries, "relationship.intimacy", state.relationship().intimacy());
        entries.put("relationship.interactionCount", Long.toString(state.relationship().interactionCount()));
        entries.put("relationship.lastSeen", state.relationship().lastSeen().toString());
        put(entries, "needs.social", state.needs().social());
        put(entries, "needs.creative", state.needs().creative());
        put(entries, "needs.knowledge", state.needs().knowledge());
        put(entries, "behaviorProb.proactiveGreet", state.behaviorProb().proactiveGreet());
        put(entries, "behaviorProb.shareObservation", state.behaviorProb().shareObservation());
        put(entries, "behaviorProb.recommendPost", state.behaviorProb().recommendPost());
        return entries;
    }

    private CharacterState deserialize(Map<Object, Object> entries) {
        return new CharacterState(
                new Personality(
                        doubleValue(entries, "personality.warmth", 0.7),
                        doubleValue(entries, "personality.curiosity", 0.8),
                        doubleValue(entries, "personality.playfulness", 0.5)),
                new Mood(
                        doubleValue(entries, "mood.valence", 0.0),
                        doubleValue(entries, "mood.arousal", 0.5),
                        doubleValue(entries, "mood.baseline", 0.0)),
                new Relationship(
                        doubleValue(entries, "relationship.intimacy", 0.0),
                        longValue(entries, "relationship.interactionCount", 0L),
                        instantValue(entries, "relationship.lastSeen", Instant.now())),
                new Needs(
                        doubleValue(entries, "needs.social", 0.0),
                        doubleValue(entries, "needs.creative", 0.0),
                        doubleValue(entries, "needs.knowledge", 0.0)),
                new BehaviorProb(
                        doubleValue(entries, "behaviorProb.proactiveGreet", 0.5),
                        doubleValue(entries, "behaviorProb.shareObservation", 0.3),
                        doubleValue(entries, "behaviorProb.recommendPost", 0.3)));
    }

    private static void put(Map<String, String> entries, String field, double value) {
        entries.put(field, Double.toString(value));
    }

    private Map<String, String> serializeEmotion(EmotionState emotion) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("label", emotion.label());
        entries.put("intensity", Double.toString(clamp(emotion.intensity(), 0.0, 1.0)));
        entries.put("triggeredAt", emotion.triggeredAt().toString());
        entries.put("trigger", emotion.trigger());
        return entries;
    }

    private EmotionState deserializeEmotion(Map<Object, Object> entries) {
        return new EmotionState(
                stringValue(entries, "label", "平静"),
                clamp(doubleValue(entries, "intensity", 0.2), 0.0, 1.0),
                instantValue(entries, "triggeredAt", clock.instant()),
                stringValue(entries, "trigger", "default"));
    }

    private EmotionState defaultEmotion() {
        return new EmotionState("平静", 0.2, clock.instant(), "default");
    }

    private static String detectEmotion(String messageContent) {
        String text = messageContent == null ? "" : messageContent.trim();
        if (text.isEmpty()) {
            return "平静";
        }
        if (containsAny(text, "困", "睡", "晚安", "熬夜")) {
            return "困";
        }
        if (containsAny(text, "难过", "伤心", "想哭", "遗憾", "再见", "离别", "牺牲")) {
            return "感伤";
        }
        if (containsAny(text, "谢谢", "喜欢你", "真好", "可爱", "厉害", "开心")) {
            return "开心";
        }
        if (containsAny(text, "为什么", "怎么", "？", "?", "好奇", "想知道")) {
            return "好奇";
        }
        if (containsAny(text, "分析", "代码", "实现", "架构", "测试", "问题", "修复")) {
            return "认真";
        }
        return "平静";
    }

    private static double calculateIntensity(String messageContent) {
        String text = messageContent == null ? "" : messageContent.trim();
        if (text.isEmpty()) {
            return 0.2;
        }
        double intensity = 0.35;
        intensity += Math.min(text.length(), 120) / 240.0;
        if (containsAny(text, "！", "!", "？", "?")) {
            intensity += 0.12;
        }
        if (containsAny(text, "很", "特别", "真的", "太", "有点")) {
            intensity += 0.08;
        }
        return clamp(intensity, 0.2, 1.0);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static double doubleValue(Map<Object, Object> entries, String field, double defaultValue) {
        Object value = entries.get(field);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long longValue(Map<Object, Object> entries, String field, long defaultValue) {
        Object value = entries.get(field);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String stringValue(Map<Object, Object> entries, String field, String defaultValue) {
        Object value = entries.get(field);
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        return value.toString();
    }

    private static Instant instantValue(Map<Object, Object> entries, String field, Instant defaultValue) {
        Object value = entries.get(field);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Instant.parse(value.toString());
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    private static String key(long userId) {
        return KEY_PREFIX + userId;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
