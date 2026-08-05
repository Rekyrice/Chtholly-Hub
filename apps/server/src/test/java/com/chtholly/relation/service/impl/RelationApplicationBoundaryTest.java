package com.chtholly.relation.service.impl;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chtholly.notification.event.FollowCreatedEvent;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.relation.event.FollowCanceledEvent;
import com.chtholly.relation.mapper.RelationMapper;
import com.chtholly.relation.outbox.OutboxMapper;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/** Characterizes relation command ordering before the compatibility facade is split. */
@ExtendWith(MockitoExtension.class)
class RelationApplicationBoundaryTest {

    @Mock private RelationMapper relationMapper;
    @Mock private OutboxMapper outboxMapper;
    @Mock private StringRedisTemplate redis;
    @Mock private UserMapper userMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SnowflakeIdGenerator idGenerator;

    private RelationCommandService commandService;

    @BeforeEach
    void setUp() {
        commandService = new RelationCommandService(
                relationMapper,
                outboxMapper,
                redis,
                new ObjectMapper(),
                userMapper,
                eventPublisher,
                idGenerator);
        lenient().when(outboxMapper.insert(
                anyLong(), anyString(), any(), anyString(), anyString()))
                .thenReturn(1);
    }

    @Test
    void commandMethodsRetainSpringTransactionBoundaries() throws Exception {
        assertThat(RelationCommandService.class
                .getMethod("follow", long.class, long.class)
                .isAnnotationPresent(Transactional.class))
                .isTrue();
        assertThat(RelationCommandService.class
                .getMethod("unfollow", long.class, long.class)
                .isAnnotationPresent(Transactional.class))
                .isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void followWritesOutboxBeforePublishingNotificationEvent() {
        allowExistingUsers();
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenReturn(1L);
        when(relationMapper.insertFollowing(anyLong(), eq(11L), eq(22L), eq(1)))
                .thenReturn(1);
        when(idGenerator.nextId()).thenReturn(101L, 201L);
        assertThat(commandService.follow(11L, 22L)).isTrue();

        InOrder order = inOrder(outboxMapper, eventPublisher);
        order.verify(outboxMapper).insert(
                anyLong(), eq("following"), anyLong(), eq("FollowCreated"), anyString());
        order.verify(eventPublisher).publishEvent(any(FollowCreatedEvent.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rateLimitedFollowDoesNotTouchDatabaseOrEvents() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenReturn(0L);

        assertThat(commandService.follow(11L, 22L)).isFalse();

        verify(relationMapper, never())
                .insertFollowing(anyLong(), anyLong(), anyLong(), any(Integer.class));
        verify(outboxMapper, never())
                .insert(anyLong(), anyString(), any(), anyString(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
        verify(idGenerator, never()).nextId();
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingRedisDecisionFailsClosedWithoutTouchingTheDatabase() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenReturn(null);

        assertThat(commandService.follow(11L, 22L)).isFalse();

        verify(relationMapper, never())
                .insertFollowing(anyLong(), anyLong(), anyLong(), any(Integer.class));
        verify(idGenerator, never()).nextId();
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateFollowDoesNotWriteOutboxOrPublishEvent() {
        allowExistingUsers();
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenReturn(1L);
        when(relationMapper.insertFollowing(anyLong(), eq(11L), eq(22L), eq(1)))
                .thenReturn(0);
        when(idGenerator.nextId()).thenReturn(202L);

        assertThat(commandService.follow(11L, 22L)).isFalse();

        verify(outboxMapper, never())
                .insert(anyLong(), anyString(), any(), anyString(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reactivatedFollowUsesPersistedRelationIdExactlyOnce() {
        allowExistingUsers();
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenReturn(1L);
        when(relationMapper.insertFollowing(anyLong(), eq(11L), eq(22L), eq(1)))
                .thenReturn(0);
        when(relationMapper.activateFollowing(11L, 22L)).thenReturn(1);
        when(relationMapper.findActiveFollowingRow(11L, 22L))
                .thenReturn(Map.of("id", 101L));
        when(idGenerator.nextId()).thenReturn(202L, 303L);

        assertThat(commandService.follow(11L, 22L)).isTrue();

        verify(outboxMapper).insert(
                eq(303L),
                eq("following"),
                eq(101L),
                eq("FollowCreated"),
                org.mockito.ArgumentMatchers.contains("\"id\":101"));
        verify(eventPublisher).publishEvent(any(FollowCreatedEvent.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateActiveFollowMustNotTreatLegacyDuplicateUpdateCountAsStateChange() {
        allowExistingUsers();
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenReturn(1L);
        when(relationMapper.insertFollowing(anyLong(), eq(11L), eq(22L), eq(1)))
                .thenReturn(2);
        when(idGenerator.nextId()).thenReturn(202L);

        assertThat(commandService.follow(11L, 22L)).isFalse();

        verify(outboxMapper, never())
                .insert(anyLong(), anyString(), any(), anyString(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void unfollowKeepsCommittedOutboxWhenNotificationEventFails() {
        when(relationMapper.cancelFollowing(11L, 22L)).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(303L);
        doThrow(new IllegalStateException("listener down"))
                .when(eventPublisher)
                .publishEvent(any(FollowCanceledEvent.class));

        assertThat(commandService.unfollow(11L, 22L)).isTrue();

        verify(outboxMapper).insert(
                eq(303L),
                eq("following"),
                eq(null),
                eq("FollowCanceled"),
                anyString());
    }

    @Test
    void repeatedUnfollowDoesNotWriteOutboxOrPublishEvent() {
        when(relationMapper.cancelFollowing(11L, 22L)).thenReturn(0);

        assertThat(commandService.unfollow(11L, 22L)).isFalse();

        verify(outboxMapper, never())
                .insert(anyLong(), anyString(), any(), anyString(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
        verify(idGenerator, never()).nextId();
    }

    @Test
    @SuppressWarnings("unchecked")
    void zeroRowOutboxInsertFailsTheRelationCommand() {
        allowExistingUsers();
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenReturn(1L);
        when(relationMapper.insertFollowing(101L, 11L, 22L, 1))
                .thenReturn(1);
        when(idGenerator.nextId()).thenReturn(101L, 201L);
        when(outboxMapper.insert(
                eq(201L),
                eq("following"),
                eq(101L),
                eq("FollowCreated"),
                anyString()))
                .thenReturn(0);

        assertThatThrownBy(() -> commandService.follow(11L, 22L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("affected 0 rows");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void outboxSerializationLogDoesNotExposePayloadOrFailureMessage()
            throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException(
                        "secret-payload-from-11-to-22") {});
        RelationCommandService failingService = new RelationCommandService(
                relationMapper,
                outboxMapper,
                redis,
                failingMapper,
                userMapper,
                eventPublisher,
                idGenerator);
        allowExistingUsers();
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenReturn(1L);
        when(idGenerator.nextId()).thenReturn(101L);
        when(relationMapper.insertFollowing(101L, 11L, 22L, 1))
                .thenReturn(1);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                        RelationCommandService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> failingService.follow(11L, 22L))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("FollowCreated", "JsonProcessingException")
                    .doesNotContain(
                            "secret-payload-from-11-to-22",
                            "fromUserId",
                            "toUserId");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private void allowExistingUsers() {
        when(userMapper.findById(11L)).thenReturn(User.builder()
                .id(11L)
                .nickname("actor")
                .avatar("avatar.png")
                .build());
        when(userMapper.findById(22L)).thenReturn(User.builder()
                .id(22L)
                .nickname("target")
                .build());
    }
}
