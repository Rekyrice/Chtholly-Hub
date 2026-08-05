package com.chtholly.auth.verification;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Redis implementation of the one-time verification-code store.
 *
 * <p>Each operation is a single-key Lua script, so code persistence, TTL,
 * consumption and failed-attempt accounting remain atomic under concurrency.
 * New keys use a digest instead of exposing the delivery identifier; reads
 * temporarily fall back to the previous key shape for rolling compatibility.
 * A failed delivery performs a compare-and-delete against the write version,
 * preventing an older request from deleting a newer successfully sent code.</p>
 */
@Component
public class RedisVerificationCodeStore implements VerificationCodeStore {

    private static final long LOCKOUT_TTL_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final long LEGACY_FALLBACK_FENCE_TTL_MILLIS = Duration.ofDays(1).toMillis();

    private static final DefaultRedisScript<Long> SAVE_SCRIPT =
            new DefaultRedisScript<>("""
                    redis.call('HSET', KEYS[1],
                        'code', ARGV[1],
                        'version', ARGV[2],
                        'maxAttempts', ARGV[3],
                        'attempts', '0')
                    redis.call('PEXPIRE', KEYS[1], ARGV[4])
                    redis.call('PSETEX', KEYS[2], ARGV[5], '1')
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<String> VERIFY_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('EXISTS', KEYS[1]) == 0 then
                        if redis.call('EXISTS', KEYS[2]) == 1 then
                            return 'LEGACY_BLOCKED|0|0'
                        end
                        return 'NOT_FOUND|0|0'
                    end

                    local attempts = tonumber(redis.call('HGET', KEYS[1], 'attempts'))
                    local max_attempts = tonumber(redis.call('HGET', KEYS[1], 'maxAttempts'))
                    if not attempts or not max_attempts or max_attempts < 1 then
                        redis.call('DEL', KEYS[1])
                        return 'NOT_FOUND|0|0'
                    end

                    if attempts >= max_attempts then
                        return 'TOO_MANY_ATTEMPTS|' .. attempts .. '|' .. max_attempts
                    end

                    local stored_code = redis.call('HGET', KEYS[1], 'code')
                    if not stored_code then
                        redis.call('DEL', KEYS[1])
                        return 'NOT_FOUND|0|0'
                    end

                    if stored_code == ARGV[1] then
                        redis.call('DEL', KEYS[1])
                        return 'SUCCESS|' .. attempts .. '|' .. max_attempts
                    end

                    attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
                    if attempts >= max_attempts then
                        redis.call('HDEL', KEYS[1], 'code')
                        redis.call('PEXPIRE', KEYS[1], ARGV[2])
                        return 'TOO_MANY_ATTEMPTS|' .. attempts .. '|' .. max_attempts
                    end
                    return 'MISMATCH|' .. attempts .. '|' .. max_attempts
                    """, String.class);

    private static final DefaultRedisScript<Long> INVALIDATE_VERSION_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('HGET', KEYS[1], 'version') == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * Creates the Redis adapter.
     *
     * @param redisTemplate string Redis operations and script executor
     */
    public RedisVerificationCodeStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void saveCode(
            String scene,
            String identifier,
            IssuedCode issuedCode,
            Duration ttl,
            int maxAttempts) {
        Objects.requireNonNull(issuedCode, "issuedCode");
        long ttlMillis = positiveTtlMillis(ttl);
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        try {
            Long result = redisTemplate.execute(
                    SAVE_SCRIPT,
                    List.of(
                            buildKey(scene, identifier),
                            legacyFallbackFenceKey(scene, identifier)),
                    issuedCode.value(),
                    issuedCode.version(),
                    Integer.toString(maxAttempts),
                    Long.toString(ttlMillis),
                    Long.toString(Math.max(ttlMillis, LEGACY_FALLBACK_FENCE_TTL_MILLIS)));
            if (!Objects.equals(1L, result)) {
                throw new IllegalStateException(
                        "Verification code persistence returned no valid result");
            }
        } catch (DataAccessException failure) {
            throw new RedisSystemException(
                    "Failed to save verification code", failure);
        }
    }

    @Override
    public VerificationCheckResult verify(
            String scene,
            String identifier,
            String code) {
        Objects.requireNonNull(code, "code");
        try {
            CurrentVerification current = verifyCurrentKey(scene, identifier, code);
            if (!current.legacyFallbackAllowed()) {
                return current.result();
            }
            if (current.result().status() != VerificationCodeStatus.NOT_FOUND) {
                return current.result();
            }
            return verifyLegacyKey(legacyKey(scene, identifier), code);
        } catch (DataAccessException failure) {
            throw new RedisSystemException(
                    "Failed to verify verification code", failure);
        }
    }

    @Override
    public void invalidate(String scene, String identifier) {
        try {
            redisTemplate.delete(buildKey(scene, identifier));
            redisTemplate.delete(legacyKey(scene, identifier));
            redisTemplate.delete(legacyFallbackFenceKey(scene, identifier));
        } catch (DataAccessException failure) {
            throw new RedisSystemException(
                    "Failed to invalidate verification code", failure);
        }
    }

    @Override
    public boolean invalidateIfCurrent(
            String scene,
            String identifier,
            String version) {
        Objects.requireNonNull(version, "version");
        try {
            Long result = redisTemplate.execute(
                    INVALIDATE_VERSION_SCRIPT,
                    List.of(buildKey(scene, identifier)),
                    version);
            if (result == null) {
                throw new IllegalStateException(
                        "Verification code invalidation returned no result");
            }
            return Objects.equals(1L, result);
        } catch (DataAccessException failure) {
            throw new RedisSystemException(
                    "Failed to conditionally invalidate verification code", failure);
        }
    }

    private static VerificationCheckResult parseResult(String result) {
        if (result == null) {
            throw new IllegalStateException(
                    "Verification code check returned no result");
        }
        String[] parts = result.split("\\|", -1);
        if (parts.length != 3) {
            throw new IllegalStateException(
                    "Verification code check returned a malformed result");
        }
        try {
            VerificationCodeStatus status = VerificationCodeStatus.valueOf(parts[0]);
            int attempts = Integer.parseInt(parts[1]);
            int maxAttempts = Integer.parseInt(parts[2]);
            if (!validCounters(status, attempts, maxAttempts)) {
                throw new IllegalArgumentException("invalid attempt counters");
            }
            return new VerificationCheckResult(status, attempts, maxAttempts);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "Verification code check returned an invalid result", failure);
        }
    }

    private CurrentVerification verifyCurrentKey(
            String scene,
            String identifier,
            String code) {
        String result = redisTemplate.execute(
                VERIFY_SCRIPT,
                List.of(
                        buildKey(scene, identifier),
                        legacyFallbackFenceKey(scene, identifier)),
                code,
                Long.toString(LOCKOUT_TTL_MILLIS));
        if (result != null && result.startsWith("LEGACY_BLOCKED|")) {
            return new CurrentVerification(
                    new VerificationCheckResult(VerificationCodeStatus.NOT_FOUND, 0, 0),
                    false);
        }
        return new CurrentVerification(parseResult(result), true);
    }

    private VerificationCheckResult verifyLegacyKey(String key, String code) {
        String result = redisTemplate.execute(
                VERIFY_SCRIPT,
                List.of(key, key),
                code,
                Long.toString(LOCKOUT_TTL_MILLIS));
        return parseResult(result);
    }

    private static boolean validCounters(
            VerificationCodeStatus status,
            int attempts,
            int maxAttempts) {
        if (status == VerificationCodeStatus.NOT_FOUND
                || status == VerificationCodeStatus.EXPIRED) {
            return attempts == 0 && maxAttempts == 0;
        }
        if (maxAttempts < 1 || attempts < 0 || attempts > maxAttempts) {
            return false;
        }
        return switch (status) {
            case SUCCESS -> attempts < maxAttempts;
            case MISMATCH -> attempts > 0 && attempts < maxAttempts;
            case TOO_MANY_ATTEMPTS -> attempts == maxAttempts;
            case NOT_FOUND, EXPIRED -> false;
        };
    }

    private static String buildKey(String scene, String identifier) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(identifier, "identifier");
        return "auth:code:{%s}".formatted(
                VerificationRedisKey.digest(scene, identifier));
    }

    private static String legacyKey(String scene, String identifier) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(identifier, "identifier");
        return "auth:code:%s:%s".formatted(scene, identifier);
    }

    private static String legacyFallbackFenceKey(String scene, String identifier) {
        return buildKey(scene, identifier) + ":legacy-disabled";
    }

    private static long positiveTtlMillis(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Verification code TTL must be positive");
        }
        return Math.max(1L, ttl.toMillis());
    }

    private record CurrentVerification(
            VerificationCheckResult result,
            boolean legacyFallbackAllowed) {
    }
}
