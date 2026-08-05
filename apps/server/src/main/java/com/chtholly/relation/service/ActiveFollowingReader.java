package com.chtholly.relation.service;

/** Reads active directed-follow facts from the authoritative relation store. */
public interface ActiveFollowingReader {

    /**
     * Returns whether the viewer currently follows the author.
     *
     * @param viewerId relation source
     * @param authorId relation target
     * @return {@code true} only for an active authoritative relation
     */
    boolean isActiveFollowing(long viewerId, long authorId);
}
