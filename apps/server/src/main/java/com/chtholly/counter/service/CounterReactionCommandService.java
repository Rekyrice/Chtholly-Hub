package com.chtholly.counter.service;

import com.chtholly.counter.event.CounterEvent;
import com.chtholly.counter.event.CounterReactionCommittedEvent;
import com.chtholly.counter.mapper.CounterPersistenceMapper;
import com.chtholly.counter.mapper.CounterReactionMapper;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.relation.outbox.OutboxMapper;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Applies authoritative reaction commands together with their durable Outbox event. */
@Service
public class CounterReactionCommandService {

    public static final String OUTBOX_AGGREGATE_TYPE = "counter_reaction";
    public static final String OUTBOX_EVENT_TYPE = "CounterReactionChanged";

    private static final Logger log = LoggerFactory.getLogger(CounterReactionCommandService.class);

    private final CounterReactionMapper reactionMapper;
    private final CounterPersistenceMapper persistenceMapper;
    private final OutboxMapper outboxMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final ApplicationEventPublisher eventPublisher;

    public CounterReactionCommandService(
            CounterReactionMapper reactionMapper,
            CounterPersistenceMapper persistenceMapper,
            OutboxMapper outboxMapper,
            SnowflakeIdGenerator idGenerator,
            ObjectMapper objectMapper,
            PostMapper postMapper,
            UserMapper userMapper,
            ApplicationEventPublisher eventPublisher) {
        this.reactionMapper = Objects.requireNonNull(reactionMapper, "reactionMapper");
        this.persistenceMapper = Objects.requireNonNull(persistenceMapper, "persistenceMapper");
        this.outboxMapper = Objects.requireNonNull(outboxMapper, "outboxMapper");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.postMapper = Objects.requireNonNull(postMapper, "postMapper");
        this.userMapper = Objects.requireNonNull(userMapper, "userMapper");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    /**
     * Sets one reaction to the requested target state in the MySQL authority.
     *
     * @param entityType entity type
     * @param entityId entity ID
     * @param metric {@code like} or {@code fav}
     * @param userId acting user ID
     * @param active target state
     * @return whether the authoritative relation changed
     */
    @Transactional
    public boolean setReaction(
            String entityType,
            String entityId,
            String metric,
            long userId,
            boolean active) {
        CounterSchema.requirePersistableIdentity(entityType, entityId);
        int metricIndex = reactionMetricIndex(metric);
        if (userId <= 0L) {
            throw new IllegalArgumentException("Counter reaction user ID must be positive");
        }

        // 快照行锁让普通命令与绝对校准在同一实体上按 factEpoch 串行化。
        persistenceMapper.ensureReactionSnapshots(entityType, entityId);
        long factEpoch = requireSingleEpoch(persistenceMapper.lockReactionEpochs(entityType, entityId));

        int affected = active
                ? reactionMapper.insertIgnore(entityType, entityId, metric, userId)
                : reactionMapper.delete(entityType, entityId, metric, userId);
        if (affected == 0) {
            return false;
        }
        if (affected != 1) {
            throw new IllegalStateException("Counter reaction mutation returned an invalid row count");
        }

        long outboxId = idGenerator.nextId();
        CounterEvent event = CounterEvent.of(
                Long.toString(outboxId),
                entityType,
                entityId,
                metric,
                metricIndex,
                userId,
                active ? 1 : -1);
        event.setFactEpoch(factEpoch);
        enrichEvent(event);
        int outboxInserted = outboxMapper.insert(
                outboxId,
                OUTBOX_AGGREGATE_TYPE,
                userId,
                OUTBOX_EVENT_TYPE,
                serialize(event));
        if (outboxInserted != 1) {
            throw new IllegalStateException(
                    "Counter reaction Outbox insert returned an invalid row count");
        }

        // 监听器只登记 AFTER_COMMIT 回调；回滚事务不会触碰 Redis 投影。
        eventPublisher.publishEvent(new CounterReactionCommittedEvent(event));
        return true;
    }

    private static int reactionMetricIndex(String metric) {
        if ("like".equals(metric)) {
            return CounterSchema.IDX_LIKE;
        }
        if ("fav".equals(metric)) {
            return CounterSchema.IDX_FAV;
        }
        throw new IllegalArgumentException("Counter reaction metric must be like or fav");
    }

    private static long requireSingleEpoch(List<Long> epochs) {
        if (epochs == null || epochs.size() != 2
                || epochs.get(0) == null || epochs.get(1) == null
                || epochs.get(0) < 0L || !epochs.get(0).equals(epochs.get(1))) {
            throw new IllegalStateException("Counter reaction snapshot epoch is inconsistent");
        }
        return epochs.get(0);
    }

    private String serialize(CounterEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize counter reaction Outbox event", exception);
        }
    }

    private void enrichEvent(CounterEvent event) {
        if (!"post".equals(event.getEntityType())) {
            return;
        }
        long postId = Long.parseLong(event.getEntityId());
        Post post = postMapper.findById(postId);
        if (post != null && post.getCreatorId() != null) {
            event.setPostCreatorId(post.getCreatorId());
            event.setPostTitle(post.getTitle());
            event.setPostSlug(post.getSlug());
        }
        if ("like".equals(event.getMetric()) && event.getDelta() == 1) {
            try {
                User actor = userMapper.findById(event.getUserId());
                if (actor != null) {
                    event.setActorNickname(actor.getNickname());
                    event.setActorAvatar(actor.getAvatar());
                }
            } catch (RuntimeException exception) {
                log.debug(
                        "Counter reaction actor context enrichment failed userId={}: {}",
                        event.getUserId(),
                        exception.getMessage());
            }
        }
    }
}
