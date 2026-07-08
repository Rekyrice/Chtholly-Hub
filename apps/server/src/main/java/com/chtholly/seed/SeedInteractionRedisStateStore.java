package com.chtholly.seed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redis-backed state store for delayed seed discussions.
 */
@Slf4j
@Component
public class SeedInteractionRedisStateStore implements SeedInteractionStateStore {

    private static final String QUEUE_KEY = "seed:interaction:queue";
    private static final String TASK_KEY_PREFIX = "seed:interaction:task:";
    private static final String POST_KEY_PREFIX = "seed:interaction:";
    private static final String DAILY_KEY_PREFIX = "seed:interaction:account:";
    private static final Duration TASK_TTL = Duration.ofDays(3);
    private static final Duration POST_STATE_TTL = Duration.ofDays(7);
    private static final Duration DAILY_TTL = Duration.ofHours(36);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public SeedInteractionRedisStateStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public int currentRounds(long postId) {
        return intValue(roundsKey(postId));
    }

    @Override
    public int commentCount(long postId) {
        return intValue(commentCountKey(postId));
    }

    @Override
    public boolean hasCommented(long postId, long userId) {
        Boolean member = redis.opsForSet().isMember(commentersKey(postId), String.valueOf(userId));
        return Boolean.TRUE.equals(member);
    }

    @Override
    public long dailyComments(long userId, LocalDate date) {
        return longValue(dailyKey(userId, date));
    }

    @Override
    public void rememberComment(long postId, long userId, LocalDate date) {
        redis.opsForValue().increment(roundsKey(postId));
        redis.opsForValue().increment(commentCountKey(postId));
        redis.opsForSet().add(commentersKey(postId), String.valueOf(userId));
        redis.opsForValue().increment(dailyKey(userId, date));
        redis.expire(roundsKey(postId), POST_STATE_TTL);
        redis.expire(commentCountKey(postId), POST_STATE_TTL);
        redis.expire(commentersKey(postId), POST_STATE_TTL);
        redis.expire(dailyKey(userId, date), DAILY_TTL);
    }

    @Override
    public void enqueue(SeedInteractionTask task) {
        try {
            redis.opsForValue().set(taskKey(task.id()), objectMapper.writeValueAsString(task), TASK_TTL);
            redis.opsForZSet().add(QUEUE_KEY, task.id(), task.scheduledAt().toEpochMilli());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize seed interaction task", e);
        }
    }

    @Override
    public List<SeedInteractionTask> dueTasks(Instant now, int limit) {
        Set<String> taskIds = redis.opsForZSet().rangeByScore(QUEUE_KEY, 0, now.toEpochMilli());
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }

        List<SeedInteractionTask> tasks = new ArrayList<>();
        for (String taskId : taskIds) {
            if (tasks.size() >= limit) {
                break;
            }
            String payload = redis.opsForValue().get(taskKey(taskId));
            if (payload == null || payload.isBlank()) {
                redis.opsForZSet().remove(QUEUE_KEY, taskId);
                continue;
            }
            try {
                tasks.add(objectMapper.readValue(payload, SeedInteractionTask.class));
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize seed interaction task: {}", taskId, e);
                removeTask(taskId);
            }
        }
        return tasks;
    }

    @Override
    public void removeTask(String taskId) {
        redis.opsForZSet().remove(QUEUE_KEY, taskId);
        redis.delete(taskKey(taskId));
    }

    private int intValue(String key) {
        return (int) longValue(key);
    }

    private long longValue(String key) {
        String value = redis.opsForValue().get(key);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String taskKey(String taskId) {
        return TASK_KEY_PREFIX + taskId;
    }

    private String roundsKey(long postId) {
        return POST_KEY_PREFIX + postId + ":rounds";
    }

    private String commentCountKey(long postId) {
        return POST_KEY_PREFIX + postId + ":comments";
    }

    private String commentersKey(long postId) {
        return POST_KEY_PREFIX + postId + ":commenters";
    }

    private String dailyKey(long userId, LocalDate date) {
        LocalDate utcDate = date == null ? LocalDate.now(ZoneOffset.UTC) : date;
        return DAILY_KEY_PREFIX + userId + ":dailyComments:" + utcDate;
    }
}
