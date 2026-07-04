package com.chtholly.agent.cognitive;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stores Chtholly's recent experience stream in Redis.
 *
 * <p>The stream is intentionally small and append-only from the agent's view:
 * Redis List keeps the newest 50 observations for room surfaces such as /chtholly.
 */
@Slf4j
@Service
public class ExperienceService {

    private static final String KEY_PREFIX = "agent:experiences:";
    private static final int MAX_EXPERIENCES = 50;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    @SuppressWarnings("unused")
    private final Clock clock;

    public ExperienceService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this(redis, objectMapper, Clock.systemUTC());
    }

    ExperienceService(StringRedisTemplate redis, ObjectMapper objectMapper, Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Stores observations and trims the global stream to the latest 50 entries.
     *
     * @param experiences Observations to append.
     */
    public void storeExperiences(List<Observation> experiences) {
        if (experiences == null || experiences.isEmpty()) {
            return;
        }
        for (Observation experience : experiences) {
            if (experience == null) {
                continue;
            }
            try {
                redis.opsForList().rightPush(KEY_PREFIX + "global", objectMapper.writeValueAsString(experience));
            } catch (Exception e) {
                log.warn("Store agent experience failed: {}", e.getMessage(), e);
            }
        }
        redis.opsForList().trim(KEY_PREFIX + "global", -MAX_EXPERIENCES, -1);
    }

    /**
     * Returns recent observations newest first.
     *
     * @param limit Maximum observations.
     * @return Recent observations.
     */
    public List<Observation> getRecentExperiences(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<String> payloads = redis.opsForList().range(KEY_PREFIX + "global", -limit, -1);
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }
        List<Observation> observations = new ArrayList<>();
        for (String payload : payloads) {
            try {
                observations.add(objectMapper.readValue(payload, Observation.class));
            } catch (Exception e) {
                log.warn("Skip malformed agent experience payload: {}", e.getMessage());
            }
        }
        Collections.reverse(observations);
        return List.copyOf(observations);
    }
}
