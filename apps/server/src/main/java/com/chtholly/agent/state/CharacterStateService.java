package com.chtholly.agent.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private static final Duration TTL = Duration.ofDays(30);

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

    private CharacterState createDefault(long userId) {
        CharacterState state = new CharacterState(
                new Personality(0.7, 0.8, 0.5),
                new Mood(0.0, 0.5, 0.0),
                new Relationship(0.0, 0, Instant.now()),
                new Needs(0.0, 0.0, 0.0),
                new BehaviorProb(0.5, 0.3, 0.3));
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
}
