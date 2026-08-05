package com.chtholly.auth.verification;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reserves verification-code send quotas atomically in Redis.
 *
 * <p>The interval and daily keys share one Redis Cluster hash tag, so one Lua
 * script can check and reserve both limits without a time-of-check/time-of-use
 * gap. Identifiers are SHA-256 digested before they enter key names to avoid
 * exposing email addresses or phone numbers through Redis metadata.</p>
 */
@Component
public class VerificationSendGuard {

    private static final long DAILY_KEY_TTL_MILLIS = Duration.ofDays(2).toMillis();

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT =
            new DefaultRedisScript<>("""
                    local interval_ttl = tonumber(ARGV[2])
                    local daily_limit = tonumber(ARGV[3])
                    local legacy_daily = tonumber(ARGV[5]) or 0

                    if interval_ttl > 0 and redis.call('EXISTS', KEYS[1]) == 1 then
                        return 1
                    end

                    if daily_limit > 0 then
                        local current_value = redis.call('GET', KEYS[2])
                        local current = current_value and tonumber(current_value) or legacy_daily
                        if current >= daily_limit then
                            return 2
                        end
                    end

                    if interval_ttl > 0 then
                        redis.call('PSETEX', KEYS[1], interval_ttl, ARGV[1])
                    end
                    if daily_limit > 0 then
                        local count
                        if redis.call('EXISTS', KEYS[2]) == 1 then
                            count = redis.call('INCR', KEYS[2])
                        else
                            count = legacy_daily + 1
                            redis.call('SET', KEYS[2], count)
                        end
                        if redis.call('PTTL', KEYS[2]) < 0 then
                            redis.call('PEXPIRE', KEYS[2], ARGV[4])
                        end
                    end
                    return 0
                    """, Long.class);

    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT =
            new DefaultRedisScript<>("""
                    local compensated = 0
                    if ARGV[2] == '1' and redis.call('GET', KEYS[1]) == ARGV[1] then
                        redis.call('DEL', KEYS[1])
                        compensated = compensated + 1
                    end
                    if ARGV[3] == '1' then
                        local current = tonumber(redis.call('GET', KEYS[2]) or '0')
                        if current <= 1 then
                            redis.call('DEL', KEYS[2])
                        else
                            redis.call('DECR', KEYS[2])
                        end
                        compensated = compensated + 1
                    end
                    return compensated
                    """, Long.class);

    private final StringRedisTemplate redis;
    private final LegacyVerificationQuotaReader legacyQuotaReader;

    /**
     * Creates the quota guard.
     *
     * @param redis Redis scripting adapter
     * @param legacyQuotaReader migration reader for still-live prior quota keys
     */
    public VerificationSendGuard(
            StringRedisTemplate redis,
            LegacyVerificationQuotaReader legacyQuotaReader) {
        this.redis = redis;
        this.legacyQuotaReader = legacyQuotaReader;
    }

    /**
     * Atomically reserves the configured interval and daily quota.
     *
     * @param scene verification-code purpose
     * @param identifier destination identifier
     * @param sendInterval minimum interval between deliveries
     * @param dailyLimit maximum deliveries per dated key
     * @return a reservation that can be compensated if persistence or delivery fails
     * @throws BusinessException when either quota rejects the request
     */
    public Reservation reserve(
            VerificationScene scene,
            String identifier,
            Duration sendInterval,
            int dailyLimit) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(sendInterval, "sendInterval");
        boolean intervalReserved = !sendInterval.isZero() && !sendInterval.isNegative();
        boolean dailyReserved = dailyLimit > 0;
        if (!intervalReserved && !dailyReserved) {
            return Reservation.disabled();
        }

        LegacyVerificationQuotaReader.LegacyQuotaSnapshot legacy =
                legacyQuotaReader.read(
                        scene, identifier, intervalReserved, dailyReserved);
        if (legacy.intervalBlocked()) {
            throw new BusinessException(ErrorCode.VERIFICATION_RATE_LIMIT);
        }

        String digest = VerificationRedisKey.digest(scene, identifier);
        List<String> keys = List.of(
                "auth:code:quota:{%s}:interval".formatted(digest),
                "auth:code:quota:{%s}:daily:%s".formatted(
                        digest, LocalDate.now()));
        String nonce = UUID.randomUUID().toString();
        Long result = redis.execute(
                RESERVE_SCRIPT,
                keys,
                nonce,
                Long.toString(intervalReserved
                        ? Math.max(1L, sendInterval.toMillis())
                        : 0L),
                Integer.toString(dailyReserved ? dailyLimit : 0),
                Long.toString(DAILY_KEY_TTL_MILLIS),
                Long.toString(legacy.dailyCount()));
        if (Objects.equals(1L, result)) {
            throw new BusinessException(ErrorCode.VERIFICATION_RATE_LIMIT);
        }
        if (Objects.equals(2L, result)) {
            throw new BusinessException(ErrorCode.VERIFICATION_DAILY_LIMIT);
        }
        if (!Objects.equals(0L, result)) {
            throw new IllegalStateException(
                    "Verification quota reservation returned no valid result");
        }
        return new Reservation(keys, nonce, intervalReserved, dailyReserved);
    }

    /**
     * Releases a reservation after code persistence or delivery fails.
     * Repeated callers join the same compensation attempt.
     *
     * @param reservation reservation returned by {@link #reserve}
     */
    public void compensate(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        if (reservation.disabled) {
            return;
        }
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        CompletableFuture<Void> existing =
                reservation.compensation.compareAndExchange(null, attempt);
        if (existing != null) {
            joinCompensation(existing);
            return;
        }
        try {
            Long result = redis.execute(
                    COMPENSATE_SCRIPT,
                    reservation.keys,
                    reservation.nonce,
                    reservation.intervalReserved ? "1" : "0",
                    reservation.dailyReserved ? "1" : "0");
            if (result == null) {
                throw new IllegalStateException(
                        "Verification quota compensation returned no result");
            }
            attempt.complete(null);
        } catch (RuntimeException failure) {
            attempt.completeExceptionally(failure);
            throw failure;
        }
    }

    private static void joinCompensation(CompletableFuture<Void> existing) {
        try {
            existing.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw failure;
        }
    }

    /** A single quota reservation and its exactly-once compensation state. */
    public static final class Reservation {
        private final List<String> keys;
        private final String nonce;
        private final boolean intervalReserved;
        private final boolean dailyReserved;
        private final boolean disabled;
        private final AtomicReference<CompletableFuture<Void>> compensation =
                new AtomicReference<>();

        Reservation(
                List<String> keys,
                String nonce,
                boolean intervalReserved,
                boolean dailyReserved) {
            this(keys, nonce, intervalReserved, dailyReserved, false);
        }

        private Reservation(
                List<String> keys,
                String nonce,
                boolean intervalReserved,
                boolean dailyReserved,
                boolean disabled) {
            this.keys = List.copyOf(keys);
            this.nonce = nonce;
            this.intervalReserved = intervalReserved;
            this.dailyReserved = dailyReserved;
            this.disabled = disabled;
        }

        private static Reservation disabled() {
            return new Reservation(List.of(), "", false, false, true);
        }
    }
}
