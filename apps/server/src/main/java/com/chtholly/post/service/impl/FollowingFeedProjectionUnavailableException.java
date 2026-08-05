package com.chtholly.post.service.impl;

/** Signals that a Redis-derived following-feed candidate projection cannot be read. */
final class FollowingFeedProjectionUnavailableException extends RuntimeException {

    private final String projection;

    FollowingFeedProjectionUnavailableException(String projection, RuntimeException cause) {
        super("Following-feed projection unavailable: " + projection, cause);
        this.projection = projection;
    }

    String projection() {
        return projection;
    }
}
