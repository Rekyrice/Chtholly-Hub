package com.chtholly.auth.token;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Stores refresh-token JTI membership in Redis while MySQL owns revocation epochs.
 *
 * <p>All current token and fence keys for one user share the Redis Cluster hash
 * tag {@code {userId}}. Membership values are tagged as {@code mysql:epoch};
 * untagged values and the legacy namespace are never accepted or migrated.</p>
 */
@Component
public class RedisRefreshTokenStore
        implements RefreshTokenStore, PendingUserRefreshTokenStore {

    private static final String MYSQL_EPOCH_PREFIX = "mysql:";
    private static final long INITIAL_EPOCH = 1L;
    private static final long REVOCATION_FENCE_MILLIS = 30_000L;

    private static final DefaultRedisScript<Long> STORE_TOKEN_SCRIPT =
            new DefaultRedisScript<>("""
                    redis.call('DEL', KEYS[2])
                    redis.call('PSETEX', KEYS[1], ARGV[1], ARGV[2])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> VALIDATE_TOKEN_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return 1
                    end
                    return 0
                    """, Long.class);

    private static final DefaultRedisScript<Long> ROTATE_TOKEN_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) ~= ARGV[2] then
                        return 0
                    end
                    redis.call('DEL', KEYS[1])
                    redis.call('DEL', KEYS[3])
                    redis.call('PSETEX', KEYS[2], ARGV[1], ARGV[2])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private static final DefaultRedisScript<Long> REVOKE_TOKEN_SCRIPT =
            new DefaultRedisScript<>("""
                    local token_ttl = redis.call('PTTL', KEYS[1])
                    redis.call('DEL', KEYS[1])
                    local fence_ttl = tonumber(ARGV[1])
                    if token_ttl > fence_ttl then
                        fence_ttl = token_ttl
                    end
                    if fence_ttl < tonumber(ARGV[2]) then
                        fence_ttl = tonumber(ARGV[2])
                    end
                    redis.call('PSETEX', KEYS[2], fence_ttl, '1')
                    return 1
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RefreshSessionEpochAuthority epochAuthority;

    /**
     * Creates the Redis membership adapter backed by the MySQL epoch authority.
     *
     * @param redisTemplate Redis command adapter
     * @param epochAuthority MySQL refresh-session epoch authority
     */
    public RedisRefreshTokenStore(
            StringRedisTemplate redisTemplate,
            RefreshSessionEpochAuthority epochAuthority) {
        this.redisTemplate = redisTemplate;
        this.epochAuthority = epochAuthority;
    }

    /** {@inheritDoc} */
    @Override
    public long captureEpoch(long userId) {
        return epochAuthority.current(userId);
    }

    /** {@inheritDoc} */
    @Override
    public void storeToken(long userId, String tokenId, Duration ttl) {
        long epoch = captureEpoch(userId);
        if (!storeTokenIfEpochMatches(userId, tokenId, ttl, epoch)) {
            throw new IllegalStateException(
                    "Refresh-session epoch changed during token store");
        }
    }

    /** {@inheritDoc} */
    @Override
    public void storeInitialTokenForPendingUser(
            long userId,
            String tokenId,
            Duration ttl) {
        requirePendingUserTransaction();
        requirePendingTokenIdentity(userId, tokenId);
        long ttlMillis = positiveTtlMillis(ttl);
        if (!epochAuthority.hasInitialEpochInCurrentTransaction(userId)
                || epochAuthority.existsInCommittedSnapshot(userId)) {
            throw new IllegalStateException(
                    "Refresh-token bootstrap requires a pending user");
        }
        Long stored = redisTemplate.execute(
                STORE_TOKEN_SCRIPT,
                List.of(
                        tokenKey(userId, tokenId),
                        revokedKey(userId, tokenId)),
                Long.toString(ttlMillis),
                taggedEpoch(INITIAL_EPOCH));
        requireScriptSuccess(
                stored,
                "Pending refresh-token membership store failed");
    }

    /** {@inheritDoc} */
    @Override
    public void discardInitialTokenForPendingUser(
            long userId,
            String tokenId) {
        requirePendingTokenIdentity(userId, tokenId);
        if (epochAuthority.existsInCommittedSnapshot(userId)) {
            return;
        }
        compareAndDelete(
                tokenKey(userId, tokenId),
                taggedEpoch(INITIAL_EPOCH));
    }

    /** {@inheritDoc} */
    @Override
    public boolean storeTokenIfEpochMatches(
            long userId,
            String tokenId,
            Duration ttl,
            long expectedEpoch) {
        if (expectedEpoch < 1L) {
            return false;
        }
        long ttlMillis = positiveTtlMillis(ttl);
        if (epochAuthority.current(userId) != expectedEpoch) {
            return false;
        }
        String taggedEpoch = taggedEpoch(expectedEpoch);
        Long stored = redisTemplate.execute(
                STORE_TOKEN_SCRIPT,
                List.of(
                        tokenKey(userId, tokenId),
                        revokedKey(userId, tokenId)),
                Long.toString(ttlMillis),
                taggedEpoch);
        requireScriptSuccess(stored, "Refresh-token membership store failed");
        if (!epochStillCurrent(userId, expectedEpoch,
                tokenKey(userId, tokenId), taggedEpoch)) {
            return false;
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isTokenValid(long userId, String tokenId) {
        long epoch = epochAuthority.current(userId);
        Long member = redisTemplate.execute(
                VALIDATE_TOKEN_SCRIPT,
                List.of(tokenKey(userId, tokenId)),
                taggedEpoch(epoch));
        if (member == null) {
            throw new IllegalStateException(
                    "Refresh-token membership validation failed");
        }
        return Objects.equals(1L, member)
                && epochAuthority.current(userId) == epoch;
    }

    /** {@inheritDoc} */
    @Override
    public boolean rotateToken(
            long userId,
            String currentTokenId,
            String replacementTokenId,
            Duration replacementTtl) {
        if (Objects.equals(currentTokenId, replacementTokenId)) {
            return false;
        }
        long ttlMillis = positiveTtlMillis(replacementTtl);
        long epoch = epochAuthority.current(userId);
        String taggedEpoch = taggedEpoch(epoch);
        Long rotated = redisTemplate.execute(
                ROTATE_TOKEN_SCRIPT,
                List.of(
                        tokenKey(userId, currentTokenId),
                        tokenKey(userId, replacementTokenId),
                        revokedKey(userId, replacementTokenId)),
                Long.toString(ttlMillis),
                taggedEpoch);
        if (rotated == null) {
            throw new IllegalStateException(
                    "Refresh-token rotation failed");
        }
        if (!Objects.equals(1L, rotated)) {
            return false;
        }
        return epochStillCurrent(
                userId,
                epoch,
                tokenKey(userId, replacementTokenId),
                taggedEpoch);
    }

    /** {@inheritDoc} */
    @Override
    public void revokeToken(long userId, String tokenId) {
        String legacyKey = legacyTokenKey(userId, tokenId);
        Long legacyTtl = redisTemplate.getExpire(
                legacyKey, TimeUnit.MILLISECONDS);
        long legacyFenceMillis = legacyTtl == null
                ? 0L
                : Math.max(0L, legacyTtl);
        Long revoked = redisTemplate.execute(
                REVOKE_TOKEN_SCRIPT,
                List.of(
                        tokenKey(userId, tokenId),
                        revokedKey(userId, tokenId)),
                Long.toString(legacyFenceMillis),
                Long.toString(REVOCATION_FENCE_MILLIS));
        requireScriptSuccess(revoked, "Refresh-token revocation failed");
        redisTemplate.delete(legacyKey);
    }

    /** {@inheritDoc} */
    @Override
    public void revokeAll(long userId) {
        epochAuthority.advance(userId);
    }

    private boolean epochStillCurrent(
            long userId,
            long expectedEpoch,
            String membershipKey,
            String membershipValue) {
        try {
            if (epochAuthority.current(userId) == expectedEpoch) {
                return true;
            }
        } catch (RuntimeException authorityFailure) {
            try {
                compareAndDelete(membershipKey, membershipValue);
            } catch (RuntimeException compensationFailure) {
                authorityFailure.addSuppressed(compensationFailure);
            }
            throw authorityFailure;
        }
        compareAndDelete(membershipKey, membershipValue);
        return false;
    }

    private void compareAndDelete(String membershipKey, String expectedValue) {
        Long deleted = redisTemplate.execute(
                COMPARE_AND_DELETE_SCRIPT,
                List.of(membershipKey),
                expectedValue);
        if (deleted == null) {
            throw new IllegalStateException(
                    "Refresh-token fencing compensation failed");
        }
    }

    private static void requireScriptSuccess(Long result, String message) {
        if (!Objects.equals(1L, result)) {
            throw new IllegalStateException(message);
        }
    }

    private static String taggedEpoch(long epoch) {
        return MYSQL_EPOCH_PREFIX + epoch;
    }

    private static String tokenKey(long userId, String tokenId) {
        return "auth:rt:{%d}:%s".formatted(userId, tokenId);
    }

    private static String revokedKey(long userId, String tokenId) {
        return "auth:rt:{%d}:revoked:%s".formatted(userId, tokenId);
    }

    private static String legacyTokenKey(long userId, String tokenId) {
        return "auth:rt:%d:%s".formatted(userId, tokenId);
    }

    private static long positiveTtlMillis(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(
                    "Refresh-token TTL must be positive");
        }
        long ttlMillis = ttl.toMillis();
        if (ttlMillis < 1L) {
            throw new IllegalArgumentException(
                    "Refresh-token TTL must be at least one millisecond");
        }
        return ttlMillis;
    }

    private static void requirePendingUserTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager
                        .isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Pending refresh-token bootstrap requires an active transaction");
        }
    }

    private static void requirePendingTokenIdentity(
            long userId,
            String tokenId) {
        if (userId < 1L) {
            throw new IllegalArgumentException("Pending user ID must be positive");
        }
        if (tokenId == null || tokenId.isBlank()) {
            throw new IllegalArgumentException(
                    "Pending refresh-token ID must not be blank");
        }
    }
}
