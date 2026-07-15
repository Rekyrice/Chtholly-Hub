package com.chtholly.profile.event;

/**
 * 作者公开资料变化的 Outbox 负载。
 */
public record AuthorProfileChangedPayload(String entity, String op, long id) {

    public static AuthorProfileChangedPayload of(long userId) {
        return new AuthorProfileChangedPayload("user", "author_profile_changed", userId);
    }
}
