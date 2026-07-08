package com.chtholly.common.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis SETNX based distributed lock service for scheduled jobs.
 */
@Slf4j
@Service
public class DistributedLockService {

    private static final String STATUS_PREFIX = "scheduled:";

    private final StringRedisTemplate redis;
    private final String instanceId;
    private final Map<String, String> localHolders = new ConcurrentHashMap<>();

    public DistributedLockService(StringRedisTemplate redis,
                                  @Value("${scheduler.lock.instance-id:}") String configuredInstanceId) {
        this.redis = redis;
        this.instanceId = configuredInstanceId == null || configuredInstanceId.isBlank()
                ? defaultInstanceId()
                : configuredInstanceId;
    }

    /**
     * Attempts to acquire a Redis lock with a TTL.
     *
     * @param lockKey unique lock key
     * @param ttl lock expiration
     * @return true when current instance acquired the lock
     */
    public boolean tryLock(String lockKey, Duration ttl) {
        if (lockKey == null || lockKey.isBlank()) {
            throw new IllegalArgumentException("lockKey must not be blank");
        }
        Duration safeTtl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(30) : ttl;
        String holder = newHolderId();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, holder, safeTtl);
            if (Boolean.TRUE.equals(acquired)) {
                localHolders.put(lockKey, holder);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to acquire scheduled lock key={}: {}", lockKey, e.getMessage());
            return false;
        }
    }

    /**
     * Releases a Redis lock only if this instance still owns it.
     *
     * @param lockKey unique lock key
     */
    public void unlock(String lockKey) {
        String localHolder = localHolders.get(lockKey);
        if (localHolder == null) {
            return;
        }
        try {
            String redisHolder = redis.opsForValue().get(lockKey);
            if (localHolder.equals(redisHolder)) {
                redis.delete(lockKey);
            }
        } catch (Exception e) {
            log.warn("Failed to release scheduled lock key={}: {}", lockKey, e.getMessage());
        } finally {
            localHolders.remove(lockKey);
        }
    }

    /**
     * Records last run metadata for the optional scheduled-task status endpoint.
     */
    public void recordRun(String taskName, long durationMs, boolean success) {
        if (taskName == null || taskName.isBlank()) {
            return;
        }
        try {
            String keyPrefix = STATUS_PREFIX + taskName + ":";
            redis.opsForValue().set(keyPrefix + "lastRun", Instant.now().toString(), Duration.ofDays(30));
            redis.opsForValue().set(keyPrefix + "lastDurationMs", String.valueOf(durationMs), Duration.ofDays(30));
            redis.opsForValue().set(keyPrefix + "lastSuccess", String.valueOf(success), Duration.ofDays(30));
        } catch (Exception e) {
            log.warn("Failed to record scheduled task metadata task={}: {}", taskName, e.getMessage());
        }
    }

    String localHolder(String lockKey) {
        return localHolders.get(lockKey);
    }

    private String newHolderId() {
        return instanceId + ":" + Thread.currentThread().getId() + ":" + UUID.randomUUID();
    }

    private static String defaultInstanceId() {
        return ManagementFactory.getRuntimeMXBean().getName() + ":" + UUID.randomUUID();
    }
}
