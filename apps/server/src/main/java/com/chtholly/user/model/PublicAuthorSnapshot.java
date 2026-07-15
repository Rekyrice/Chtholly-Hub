package com.chtholly.user.model;

import java.time.Instant;

/**
 * 公开作者资料的权威快照，只包含可在站内公开展示的字段。
 */
public record PublicAuthorSnapshot(
        long id,
        String handle,
        String nickname,
        String avatar,
        String bio,
        String tagsJson,
        Instant createdAt
) {
}
