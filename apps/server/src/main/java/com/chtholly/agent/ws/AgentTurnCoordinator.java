package com.chtholly.agent.ws;

import com.chtholly.agent.runtime.AgentTurnKeySupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns cross-instance single-flight and short-lived request deduplication for agent turns.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentTurnCoordinator {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);
    private static final Duration DEDUPE_GRACE = Duration.ofSeconds(60);

    private static final DefaultRedisScript<String> ACQUIRE_SCRIPT = stringScript("""
            local duplicate = redis.call('GET', KEYS[2])
            if duplicate then
              return 'DUPLICATE_REQUEST|' .. duplicate
            end
            local active = redis.call('GET', KEYS[1])
            if active then
              return 'TURN_IN_PROGRESS|' .. active
            end
            redis.call('PSETEX', KEYS[1], ARGV[2], ARGV[1])
            redis.call('PSETEX', KEYS[2], ARGV[3], ARGV[1])
            return 'ACQUIRED|' .. ARGV[1]
            """);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = longScript("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """);

    private final StringRedisTemplate redis;
    private final Map<String, InMemoryLease> localActive;
    private final Map<String, InMemoryLease> localRequests;

    /**
     * Creates the production Redis-backed coordinator.
     *
     * @param redis Redis template used for atomic lease scripts
     */
    @Autowired
    public AgentTurnCoordinator(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.localActive = null;
        this.localRequests = null;
    }

    private AgentTurnCoordinator() {
        this.redis = null;
        this.localActive = new ConcurrentHashMap<>();
        this.localRequests = new ConcurrentHashMap<>();
    }

    /**
     * Creates a process-local coordinator for deterministic unit tests.
     *
     * @return isolated in-memory coordinator
     */
    public static AgentTurnCoordinator inMemory() {
        return new AgentTurnCoordinator();
    }

    /**
     * Atomically claims one logical conversation turn or reports its current owner.
     *
     * @param userId authenticated user identifier
     * @param chatSessionId logical conversation identifier
     * @param requestId client request identifier
     * @param turnId proposed server turn identifier
     * @param ttl active lease lifetime
     * @return acquisition status and canonical turn identifier
     */
    public AcquireResult acquire(
            long userId,
            String chatSessionId,
            String requestId,
            String turnId,
            Duration ttl) {
        requireText(chatSessionId, "chatSessionId");
        requireText(requestId, "requestId");
        requireText(turnId, "turnId");
        Duration safeTtl = safeTtl(ttl);
        String activeKey = AgentTurnKeySupport.activeKey(userId, chatSessionId);
        String requestKey = AgentTurnKeySupport.requestKey(userId, chatSessionId, requestId);

        if (redis == null) {
            return acquireInMemory(activeKey, requestKey, turnId, safeTtl);
        }

        try {
            String raw = redis.execute(
                    ACQUIRE_SCRIPT,
                    List.of(activeKey, requestKey),
                    turnId,
                    String.valueOf(safeTtl.toMillis()),
                    String.valueOf(safeTtl.plus(DEDUPE_GRACE).toMillis()));
            return parseAcquireResult(raw, turnId);
        } catch (RuntimeException exception) {
            log.warn("Agent turn coordination acquire failed: {}", exception.getMessage());
            return new AcquireResult(AcquireStatus.UNAVAILABLE, turnId);
        }
    }

    /**
     * Releases the active lease only when {@code turnId} is still its owner.
     *
     * @param userId authenticated user identifier
     * @param chatSessionId logical conversation identifier
     * @param turnId owner turn identifier
     * @return whether an owned active lease was removed
     */
    public boolean release(long userId, String chatSessionId, String turnId) {
        if (chatSessionId == null || chatSessionId.isBlank() || turnId == null || turnId.isBlank()) {
            return false;
        }
        String activeKey = AgentTurnKeySupport.activeKey(userId, chatSessionId);
        if (redis == null) {
            synchronized (this) {
                purgeExpired(activeKey, null, System.nanoTime());
                InMemoryLease current = localActive.get(activeKey);
                return current != null
                        && current.turnId().equals(turnId)
                        && localActive.remove(activeKey, current);
            }
        }
        try {
            Long released = redis.execute(RELEASE_SCRIPT, List.of(activeKey), turnId);
            return released != null && released > 0;
        } catch (RuntimeException exception) {
            log.warn("Agent turn coordination release failed: {}", exception.getMessage());
            return false;
        }
    }

    private synchronized AcquireResult acquireInMemory(
            String activeKey,
            String requestKey,
            String turnId,
            Duration ttl) {
        long now = System.nanoTime();
        purgeExpired(activeKey, requestKey, now);
        InMemoryLease duplicate = localRequests.get(requestKey);
        if (duplicate != null) {
            return new AcquireResult(AcquireStatus.DUPLICATE_REQUEST, duplicate.turnId());
        }
        InMemoryLease active = localActive.get(activeKey);
        if (active != null) {
            return new AcquireResult(AcquireStatus.TURN_IN_PROGRESS, active.turnId());
        }
        InMemoryLease activeLease = new InMemoryLease(turnId, deadlineNanos(now, ttl));
        localActive.put(activeKey, activeLease);
        localRequests.put(
                requestKey,
                new InMemoryLease(turnId, deadlineNanos(now, ttl.plus(DEDUPE_GRACE))));
        return new AcquireResult(AcquireStatus.ACQUIRED, turnId);
    }

    private void purgeExpired(String activeKey, String requestKey, long now) {
        InMemoryLease active = localActive.get(activeKey);
        if (active != null && active.expiresAtNanos() <= now) {
            localActive.remove(activeKey, active);
        }
        if (requestKey != null) {
            InMemoryLease request = localRequests.get(requestKey);
            if (request != null && request.expiresAtNanos() <= now) {
                localRequests.remove(requestKey, request);
            }
        }
    }

    private AcquireResult parseAcquireResult(String raw, String proposedTurnId) {
        if (raw == null || raw.isBlank()) {
            return new AcquireResult(AcquireStatus.UNAVAILABLE, proposedTurnId);
        }
        int separator = raw.indexOf('|');
        String statusValue = separator < 0 ? raw : raw.substring(0, separator);
        String canonicalTurnId = separator < 0 ? proposedTurnId : raw.substring(separator + 1);
        try {
            return new AcquireResult(AcquireStatus.valueOf(statusValue), canonicalTurnId);
        } catch (IllegalArgumentException exception) {
            log.warn("Unknown agent turn coordination result: {}", statusValue);
            return new AcquireResult(AcquireStatus.UNAVAILABLE, proposedTurnId);
        }
    }

    private static Duration safeTtl(Duration ttl) {
        return ttl == null || ttl.isZero() || ttl.isNegative() ? DEFAULT_TTL : ttl;
    }

    private static long deadlineNanos(long now, Duration ttl) {
        long nanos;
        try {
            nanos = ttl.toNanos();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
        if (Long.MAX_VALUE - now < nanos) {
            return Long.MAX_VALUE;
        }
        return now + nanos;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static DefaultRedisScript<String> stringScript(String source) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(String.class);
        return script;
    }

    private static DefaultRedisScript<Long> longScript(String source) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(Long.class);
        return script;
    }

    /** Result of one atomic acquire attempt. */
    public record AcquireResult(AcquireStatus status, String turnId) {
        public AcquireResult {
            status = status == null ? AcquireStatus.UNAVAILABLE : status;
            turnId = turnId == null ? "" : turnId;
        }
    }

    /** Stable acquisition outcomes consumed by the WebSocket protocol. */
    public enum AcquireStatus {
        ACQUIRED,
        TURN_IN_PROGRESS,
        DUPLICATE_REQUEST,
        UNAVAILABLE
    }

    private record InMemoryLease(String turnId, long expiresAtNanos) {
    }
}
