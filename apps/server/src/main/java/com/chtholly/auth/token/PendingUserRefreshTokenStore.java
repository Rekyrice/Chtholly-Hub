package com.chtholly.auth.token;

import java.time.Duration;

/**
 * Narrow refresh-token port for a new user visible only inside registration.
 *
 * <p>This port is intentionally separate from {@link RefreshTokenStore}; it
 * must never be used to issue tokens for an already committed user.</p>
 */
public interface PendingUserRefreshTokenStore {

    /**
     * Stores the initial epoch-one membership for an uncommitted new user.
     *
     * @param userId pending user ID
     * @param tokenId refresh-token JTI
     * @param ttl token lifetime
     */
    void storeInitialTokenForPendingUser(
            long userId,
            String tokenId,
            Duration ttl);

    /**
     * Removes an epoch-one membership after the registration transaction fails.
     *
     * @param userId rolled-back user ID
     * @param tokenId refresh-token JTI
     */
    void discardInitialTokenForPendingUser(long userId, String tokenId);
}
