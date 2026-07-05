package com.chtholly.agent.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Stores and delivers in-site proactive Agent notifications.
 *
 * <p>Delivery follows the existing raw Agent WebSocket instead of STOMP:
 * Redis keeps a short pending queue, while online sessions register callbacks
 * that receive notifications immediately.
 */
@Slf4j
@Service
public class NotificationService {

    private static final String KEY_PREFIX = "agent:notifications:";
    private static final int MAX_PENDING = 10;
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<Long, Map<String, Consumer<Notification>>> onlineSessions = new ConcurrentHashMap<>();

    @Autowired
    public NotificationService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this(redis, objectMapper, Clock.systemUTC());
    }

    NotificationService(StringRedisTemplate redis, ObjectMapper objectMapper, Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Registers an online Agent WebSocket session for immediate delivery.
     *
     * @param userId    user ID
     * @param sessionId WebSocket session ID
     * @param consumer  delivery callback
     */
    public void registerSession(Long userId, String sessionId, Consumer<Notification> consumer) {
        if (userId == null || sessionId == null || sessionId.isBlank() || consumer == null) {
            return;
        }
        onlineSessions
                .computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>())
                .put(sessionId, consumer);
    }

    /**
     * Removes an online Agent WebSocket session.
     *
     * @param userId    user ID
     * @param sessionId WebSocket session ID
     */
    public void unregisterSession(Long userId, String sessionId) {
        if (userId == null || sessionId == null) {
            return;
        }
        Map<String, Consumer<Notification>> sessions = onlineSessions.get(userId);
        if (sessions == null) {
            return;
        }
        sessions.remove(sessionId);
        if (sessions.isEmpty()) {
            onlineSessions.remove(userId);
        }
    }

    /**
     * Sends a notification to a specific user and keeps a short pending queue.
     *
     * @param userId       target user ID
     * @param notification notification payload
     */
    public void send(Long userId, Notification notification) {
        if (userId == null || notification == null) {
            return;
        }
        Notification normalized = normalize(notification);
        String key = key(userId);
        try {
            redis.opsForList().rightPush(key, objectMapper.writeValueAsString(normalized));
            redis.opsForList().trim(key, -MAX_PENDING, -1);
            redis.expire(key, TTL);
        } catch (Exception e) {
            log.warn("Store proactive notification failed userId={}: {}", userId, e.getMessage(), e);
        }
        deliverToUser(userId, normalized);
    }

    /**
     * Broadcasts a notification to currently online Agent WebSocket sessions.
     *
     * @param notification notification payload
     */
    public void broadcast(Notification notification) {
        if (notification == null) {
            return;
        }
        Notification normalized = normalize(notification);
        for (Long userId : onlineSessions.keySet()) {
            deliverToUser(userId, normalized);
        }
    }

    /**
     * Returns pending notifications for a user.
     *
     * @param userId user ID
     * @return parsed pending notifications
     */
    public List<Notification> getPendingNotifications(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<String> payloads = redis.opsForList().range(key(userId), 0, -1);
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }
        List<Notification> notifications = new ArrayList<>();
        for (String payload : payloads) {
            try {
                notifications.add(objectMapper.readValue(payload, Notification.class));
            } catch (Exception e) {
                log.warn("Skip malformed proactive notification userId={}: {}", userId, e.getMessage());
            }
        }
        return List.copyOf(notifications);
    }

    private void deliverToUser(Long userId, Notification notification) {
        Map<String, Consumer<Notification>> sessions = onlineSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (Consumer<Notification> consumer : sessions.values()) {
            try {
                consumer.accept(notification);
            } catch (Exception e) {
                log.warn("Deliver proactive notification failed userId={}: {}", userId, e.getMessage(), e);
            }
        }
    }

    private Notification normalize(Notification notification) {
        return new Notification(
                notification.type(),
                notification.message(),
                notification.timestamp() == null ? clock.instant() : notification.timestamp(),
                notification.channel() == null ? NotificationChannel.FLOATING : notification.channel());
    }

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
