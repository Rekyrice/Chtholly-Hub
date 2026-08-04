package com.chtholly.storage.service;

/**
 * Narrow read port used by storage use cases to authorize post-scoped object keys.
 */
public interface PostOwnershipReader {

    /**
     * Checks whether a post exists and belongs to a user.
     *
     * @param postId post ID
     * @param userId expected owner ID
     * @return {@code true} only when the persisted post belongs to the user
     */
    boolean isOwnedBy(long postId, long userId);
}
