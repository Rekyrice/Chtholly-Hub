package com.chtholly.relation.service.impl;

import com.chtholly.notification.event.FollowCreatedEvent;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.relation.event.FollowCanceledEvent;
import com.chtholly.relation.event.RelationEvent;
import com.chtholly.relation.mapper.RelationMapper;
import com.chtholly.relation.outbox.OutboxMapper;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Coordinates idempotent relation commands, local Outbox writes, and events. */
@Service
public class RelationCommandService {

    private static final Logger log =
            LoggerFactory.getLogger(RelationCommandService.class);

    private final RelationMapper relationMapper;
    private final OutboxMapper outboxMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final UserMapper userMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final SnowflakeIdGenerator idGenerator;
    private final DefaultRedisScript<Long> tokenScript;

    /** Creates the relation command application service. */
    public RelationCommandService(
            RelationMapper relationMapper,
            OutboxMapper outboxMapper,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            UserMapper userMapper,
            ApplicationEventPublisher eventPublisher,
            SnowflakeIdGenerator idGenerator) {
        this.relationMapper = relationMapper;
        this.outboxMapper = outboxMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.userMapper = userMapper;
        this.eventPublisher = eventPublisher;
        this.idGenerator = idGenerator;
        this.tokenScript = new DefaultRedisScript<>();
        this.tokenScript.setResultType(Long.class);
        this.tokenScript.setScriptText(TOKEN_BUCKET_LUA);
    }

    /** Follows one user within the original transaction and event ordering. */
    @Transactional
    public boolean follow(long fromUserId, long toUserId) {
        Long allowed = redis.execute(
                tokenScript,
                List.of("rl:follow:" + fromUserId),
                "100",
                "1");
        if (!Long.valueOf(1L).equals(allowed)) {
            return false;
        }

        long relationId = idGenerator.nextId();
        int inserted = relationMapper.insertFollowing(
                relationId, fromUserId, toUserId, 1);
        if (inserted == 0) {
            int activated = relationMapper.activateFollowing(
                    fromUserId, toUserId);
            if (activated != 1) {
                return false;
            }
            relationId = activeRelationId(fromUserId, toUserId);
        } else if (inserted != 1) {
            return false;
        }

        // Outbox 必须与 following 写入处于同一本地事务，不能改为提交后副作用。
        writeRelationOutbox(
                relationId,
                "FollowCreated",
                new RelationEvent(
                        "FollowCreated",
                        fromUserId,
                        toUserId,
                        relationId));
        User actor = userMapper.findById(fromUserId);
        eventPublisher.publishEvent(new FollowCreatedEvent(
                fromUserId,
                actor == null ? null : actor.getNickname(),
                actor == null ? null : actor.getAvatar(),
                toUserId));
        return true;
    }

    private long activeRelationId(long fromUserId, long toUserId) {
        Map<String, Object> row = relationMapper.findActiveFollowingRow(
                fromUserId, toUserId);
        if (row == null || !(row.get("id") instanceof Number id)) {
            throw new IllegalStateException(
                    "Activated relation is missing its authoritative id");
        }
        return id.longValue();
    }

    /** Cancels one relation within the original transaction and event ordering. */
    @Transactional
    public boolean unfollow(long fromUserId, long toUserId) {
        int updated = relationMapper.cancelFollowing(fromUserId, toUserId);
        if (updated <= 0) {
            return false;
        }

        writeRelationOutbox(
                null,
                "FollowCanceled",
                new RelationEvent(
                        "FollowCanceled", fromUserId, toUserId, null));
        try {
            eventPublisher.publishEvent(
                    new FollowCanceledEvent(fromUserId, toUserId));
        } catch (Exception failure) {
            log.warn(
                    "FollowCanceledEvent failed, from={}, to={}, errorType={}",
                    fromUserId,
                    toUserId,
                    errorType(failure));
        }
        return true;
    }

    private void writeRelationOutbox(
            Long aggregateId,
            String eventType,
            RelationEvent event) {
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException failure) {
            log.warn(
                    "Relation Outbox serialization failed, eventType={}, errorType={}",
                    eventType,
                    errorType(failure));
            throw new IllegalStateException(
                    "Failed to serialize relation Outbox event " + eventType,
                    failure);
        }
        long outboxId = idGenerator.nextId();
        try {
            outboxMapper.insert(
                    outboxId,
                    "following",
                    aggregateId,
                    eventType,
                    payload);
        } catch (RuntimeException failure) {
            log.warn(
                    "Relation Outbox insert failed, eventType={}, aggregateId={}, errorType={}",
                    eventType,
                    aggregateId,
                    errorType(failure));
            throw failure;
        }
    }

    private static String errorType(Throwable failure) {
        String simpleName = failure.getClass().getSimpleName();
        Class<?> type = failure.getClass();
        while (simpleName.isBlank() && type.getSuperclass() != null) {
            type = type.getSuperclass();
            simpleName = type.getSimpleName();
        }
        return simpleName;
    }

    private static final String TOKEN_BUCKET_LUA = """

            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now = redis.call('TIME')[1]
            local last = redis.call('HGET', key, 'last')
            local tokens = redis.call('HGET', key, 'tokens')
            if not last then last = now; tokens = capacity end
            local elapsed = tonumber(now) - tonumber(last)
            local add = elapsed * rate
            tokens = math.min(capacity, tonumber(tokens) + add)
            if tokens < 1 then redis.call('HSET', key, 'last', now); redis.call('HSET', key, 'tokens', tokens); return 0 end
            tokens = tokens - 1
            redis.call('HSET', key, 'last', now)
            redis.call('HSET', key, 'tokens', tokens)
            redis.call('PEXPIRE', key, 60000)
            return 1
            """;
}
