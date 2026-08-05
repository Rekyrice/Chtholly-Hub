package com.chtholly.auth.token;

import java.time.Duration;

/**
 * Authority port for refresh-token membership and revocation.
 *
 * <p>Implementations may use Redis, a database, or another shared store, but
 * must preserve cross-instance one-time rotation and user-wide revocation.</p>
 */
public interface RefreshTokenStore {

    /**
     * Captures the user's current revocation epoch.
     *
     * <p>The returned value can be supplied to
     * {@link #storeTokenIfEpochMatches(long, String, Duration, long)} to
     * fence token issuance across credential changes and user-wide
     * revocation.</p>
     *
     * @param userId user ID
     * @return current positive revocation epoch
     */
    long captureEpoch(long userId);

    /**
     * Stores one refresh-token membership record.
     *
     * @param userId user ID
     * @param tokenId refresh-token JTI
     * @param ttl token lifetime
     */
    void storeToken(long userId, String tokenId, Duration ttl);

    /**
     * Stores one refresh-token membership record only when the user's
     * current revocation epoch still matches a previously captured value.
     *
     * @param userId user ID
     * @param tokenId refresh-token JTI
     * @param ttl token lifetime
     * @param expectedEpoch epoch captured before credential validation
     * @return whether the token was stored in the expected epoch
     */
    boolean storeTokenIfEpochMatches(
            long userId,
            String tokenId,
            Duration ttl,
            long expectedEpoch);

    /**
     * Checks whether one refresh token is still a current member.
     *
     * @param userId user ID
     * @param tokenId refresh-token JTI
     * @return whether the token is current and unexpired
     */
    boolean isTokenValid(long userId, String tokenId);

    /**
     * Atomically consumes one current token and stores its replacement.
     *
     * <p>Every adapter must implement this operation using the authority's
     * native concurrency primitive. A process-local default cannot preserve
     * one-time semantics across adapter instances or application nodes.</p>
     *
     * @param userId user ID
     * @param currentTokenId token ID being consumed
     * @param replacementTokenId replacement token ID
     * @param replacementTtl replacement lifetime
     * @return {@code true} only when the current token existed and was
     *     consumed by this call
     */
    boolean rotateToken(
            long userId,
            String currentTokenId,
            String replacementTokenId,
            Duration replacementTtl);

    /**
     * Revokes one refresh token.
     *
     * @param userId user ID
     * @param tokenId refresh-token JTI
     */
    void revokeToken(long userId, String tokenId);

    /**
     * Revokes every refresh token issued to one user.
     *
     * @param userId user ID
     */
    void revokeAll(long userId);
}
