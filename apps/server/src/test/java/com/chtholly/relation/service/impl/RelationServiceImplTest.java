package com.chtholly.relation.service.impl;

import com.chtholly.relation.mapper.RelationMapper;
import com.chtholly.relation.outbox.OutboxMapper;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Transaction-boundary contracts for relation writes and their Outbox events.
 */
@ExtendWith(MockitoExtension.class)
class RelationServiceImplTest {

    @Mock
    private RelationMapper relationMapper;

    @Mock
    private OutboxMapper outboxMapper;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private UserMapper userMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private RelationCacheInvalidator relationCacheInvalidator;

    @Mock
    private SnowflakeIdGenerator idGenerator;

    private RelationServiceImpl service;

    @BeforeEach
    void setUp() {
        RelationCommandService commandService = new RelationCommandService(
                relationMapper,
                outboxMapper,
                redis,
                new ObjectMapper(),
                userMapper,
                eventPublisher,
                idGenerator);
        RelationProjectionCache projectionCache =
                new RelationProjectionCache(
                        relationMapper,
                        redis,
                        relationCacheInvalidator);
        RelationQueryService queryService = new RelationQueryService(
                relationMapper, projectionCache, userMapper);
        service = new RelationServiceImpl(commandService, queryService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void followPropagatesOutboxFailure() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(1L);
        when(relationMapper.insertFollowing(anyLong(), eq(11L), eq(22L), eq(1))).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(101L, 201L);
        doThrow(new IllegalStateException("outbox down"))
                .when(outboxMapper)
                .insert(anyLong(), eq("following"), anyLong(), eq("FollowCreated"), anyString());

        assertThatThrownBy(() -> service.follow(11L, 22L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox down");
    }

    @Test
    void unfollowPropagatesOutboxFailure() {
        when(relationMapper.cancelFollowing(11L, 22L)).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(201L);
        doThrow(new IllegalStateException("outbox down"))
                .when(outboxMapper)
                .insert(anyLong(), eq("following"), eq(null), eq("FollowCanceled"), anyString());

        assertThatThrownBy(() -> service.unfollow(11L, 22L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox down");
    }
}
