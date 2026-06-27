package com.chtholly.relation.event;

/** 取消关注成功后发布，用于清理关注时间线中的历史条目。 */
public record FollowCanceledEvent(
        long fromUserId,
        long toUserId
) {}
