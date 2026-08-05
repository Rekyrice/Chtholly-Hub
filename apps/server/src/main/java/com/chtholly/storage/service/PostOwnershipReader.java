package com.chtholly.storage.service;

/**
 * Narrow read port used by storage use cases to authorize draft-scoped object keys.
 */
public interface PostOwnershipReader {

    /**
     * Checks whether a post is still a draft and belongs to a user.
     *
     * @param postId post ID
     * @param userId expected owner ID
     * @return {@code true} only when the persisted post is the user's draft
     */
    boolean isDraftOwnedBy(long postId, long userId);
}
