package com.chtholly.counter.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 计数事件模型。
 *
 * <p>用于描述一次状态变化导致的计数增量（如点赞 +1 / 取消点赞 -1），
 * 由生产者发送到 Kafka，消费者聚合后折叠到汇总计数。</p>
 * <p>
 * 帖子点赞时可在事件源填充 {@code postCreatorId}/{@code postTitle}/{@code postSlug}
 * 与 {@code actorNickname}/{@code actorAvatar}，避免下游监听器重复查库。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CounterEvent {
    private String eventId;
    private String entityType;
    private String entityId;
    private String metric; // like | fav | view
    private int idx;
    private long userId;
    private int delta; // +1 / -1

    /** 帖子作者 ID（post 实体事件，事件源填充） */
    private Long postCreatorId;
    private String postTitle;
    private String postSlug;
    /** 操作用户展示信息（点赞通知用，事件源填充） */
    private String actorNickname;
    private String actorAvatar;

    public CounterEvent(String entityType, String entityId, String metric, int idx, long userId, int delta) {
        this(UUID.randomUUID().toString(), entityType, entityId, metric, idx, userId, delta,
                null, null, null, null, null);
    }

    public static CounterEvent of(String entityType, String entityId, String metric, int idx, long userId, int delta) {
        return new CounterEvent(entityType, entityId, metric, idx, userId, delta);
    }

    /** Creates a seed event with a stable ID while retaining the content-pack call shape. */
    public static CounterEvent of(
            String entityType, String entityId, String metric, int idx, long userId, int delta, String eventId) {
        return new CounterEvent(eventId, entityType, entityId, metric, idx, userId, delta,
                null, null, null, null, null);
    }

    /** Creates an event with a stable ID for broker retry and replay. */
    public static CounterEvent of(String eventId, String entityType, String entityId,
                                  String metric, int idx, long userId, int delta) {
        return new CounterEvent(eventId, entityType, entityId, metric, idx, userId, delta,
                null, null, null, null, null);
    }
}
