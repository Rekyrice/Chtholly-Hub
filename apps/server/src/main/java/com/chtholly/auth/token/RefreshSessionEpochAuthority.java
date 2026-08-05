package com.chtholly.auth.token;

import com.chtholly.user.mapper.UserMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides transaction-aware access to the MySQL refresh-session epoch.
 *
 * <p>Fresh reads use their own transaction so two fencing reads cannot share
 * one repeatable-read snapshot. Advances join an existing account-management
 * transaction when one is active.</p>
 */
@Component
public class RefreshSessionEpochAuthority {

    private static final long INITIAL_EPOCH = 1L;

    private final UserMapper userMapper;

    /**
     * Creates the MySQL epoch authority.
     *
     * @param userMapper user persistence mapper
     */
    public RefreshSessionEpochAuthority(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * Reads the latest committed positive epoch in a new transaction.
     *
     * @param userId user ID
     * @return current positive epoch
     * @throws IllegalStateException when the user or a valid epoch is absent
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true)
    public long current(long userId) {
        Long epoch = userMapper.findRefreshSessionEpoch(userId);
        if (epoch == null || epoch < 1L) {
            throw new IllegalStateException(
                    "Refresh-session epoch is unavailable");
        }
        return epoch;
    }

    /**
     * Checks that an epoch-one user is visible in the caller's transaction.
     *
     * <p>Combined with {@link #existsInCommittedSnapshot(long)}, this
     * distinguishes an uncommitted registration row from an existing user.</p>
     *
     * @param userId pending user ID
     * @return whether the current transaction sees the initial epoch
     */
    @Transactional(
            propagation = Propagation.MANDATORY,
            readOnly = true)
    public boolean hasInitialEpochInCurrentTransaction(long userId) {
        return Long.valueOf(INITIAL_EPOCH).equals(
                userMapper.findRefreshSessionEpoch(userId));
    }

    /**
     * Checks whether the user exists in a fresh committed MySQL snapshot.
     *
     * @param userId user ID
     * @return whether a committed user row exists
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true)
    public boolean existsInCommittedSnapshot(long userId) {
        return userMapper.findRefreshSessionEpoch(userId) != null;
    }

    /**
     * Atomically advances the epoch in the current MySQL transaction.
     *
     * @param userId user ID
     * @throws IllegalStateException when the user cannot be updated exactly once
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void advance(long userId) {
        int affected = userMapper.advanceRefreshSessionEpoch(userId);
        if (affected != 1) {
            throw new IllegalStateException(
                    "Refresh-session epoch advance failed");
        }
    }
}
