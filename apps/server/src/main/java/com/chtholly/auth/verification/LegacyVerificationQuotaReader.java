package com.chtholly.auth.verification;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Reads the previous verification quota key shape during rolling migration.
 *
 * <p>New reservations are never written to these clear-text keys. Once their
 * original TTLs expire, reads naturally return an empty snapshot and the
 * compatibility path has no behavioral effect.</p>
 */
@Component
public class LegacyVerificationQuotaReader {

    private static final DateTimeFormatter LEGACY_DAY =
            DateTimeFormatter.BASIC_ISO_DATE;

    private final StringRedisTemplate redis;

    public LegacyVerificationQuotaReader(StringRedisTemplate redis) {
        this.redis = redis;
    }

    LegacyQuotaSnapshot read(
            VerificationScene scene,
            String identifier,
            boolean readInterval,
            boolean readDaily) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(identifier, "identifier");
        boolean intervalBlocked = readInterval && Boolean.TRUE.equals(
                redis.hasKey(intervalKey(scene, identifier)));
        long dailyCount = readDaily
                ? parseDailyCount(redis.opsForValue().get(dailyKey(scene, identifier)))
                : 0L;
        return new LegacyQuotaSnapshot(intervalBlocked, dailyCount);
    }

    private static long parseDailyCount(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            long count = Long.parseLong(value);
            if (count < 0L) {
                throw new IllegalArgumentException("negative count");
            }
            return count;
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "Legacy verification quota contains an invalid daily count",
                    failure);
        }
    }

    private static String intervalKey(
            VerificationScene scene,
            String identifier) {
        return "auth:code:last:%s:%s".formatted(scene.name(), identifier);
    }

    private static String dailyKey(
            VerificationScene scene,
            String identifier) {
        return "auth:code:count:%s:%s:%s".formatted(
                scene.name(), identifier, LEGACY_DAY.format(LocalDate.now()));
    }

    record LegacyQuotaSnapshot(boolean intervalBlocked, long dailyCount) {
    }
}
