package com.chtholly.post.event;

import java.time.Instant;

/** 帖子发布成功后发布，用于关注时间线推模式写入。 */
public record PostPublishedEvent(
        long postId,
        long creatorId,
        Instant publishTime,
        String visible
) {}
