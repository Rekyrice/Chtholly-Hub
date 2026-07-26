package com.chtholly.counter.service;

import com.chtholly.counter.event.CounterEvent;
import com.chtholly.counter.event.CounterReactionCommittedEvent;
import com.chtholly.counter.mapper.CounterPersistenceMapper;
import com.chtholly.counter.mapper.CounterReactionMapper;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.relation.outbox.OutboxMapper;
import com.chtholly.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CounterReactionCommandServiceTest {

    @Mock
    private CounterReactionMapper reactionMapper;
    @Mock
    private CounterPersistenceMapper persistenceMapper;
    @Mock
    private OutboxMapper outboxMapper;
    @Mock
    private SnowflakeIdGenerator idGenerator;
    @Mock
    private PostMapper postMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private CounterReactionCommandService service;

    @BeforeEach
    void setUp() {
        service = new CounterReactionCommandService(
                reactionMapper,
                persistenceMapper,
                outboxMapper,
                idGenerator,
                new ObjectMapper(),
                postMapper,
                userMapper,
                eventPublisher);
    }

    @Test
    void firstLikePersistsFactAndOneOutboxEventWithLockedEpoch() throws Exception {
        when(persistenceMapper.lockReactionEpochs("post", "7")).thenReturn(List.of(4L, 4L));
        when(reactionMapper.insertIgnore("post", "7", "like", 42L)).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(123L);
        when(outboxMapper.insert(
                eq(123L), eq("counter_reaction"), eq(42L),
                eq("CounterReactionChanged"), anyString())).thenReturn(1);

        assertThat(service.setReaction("post", "7", "like", 42L, true)).isTrue();

        verify(persistenceMapper).ensureReactionSnapshots("post", "7");
        verify(reactionMapper).insertIgnore("post", "7", "like", 42L);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxMapper).insert(
                eq(123L), eq("counter_reaction"), eq(42L),
                eq("CounterReactionChanged"), payload.capture());
        CounterEvent event = new ObjectMapper().readValue(payload.getValue(), CounterEvent.class);
        assertThat(event.getEventId()).isEqualTo("123");
        assertThat(event.getEntityType()).isEqualTo("post");
        assertThat(event.getEntityId()).isEqualTo("7");
        assertThat(event.getMetric()).isEqualTo("like");
        assertThat(event.getIdx()).isEqualTo(1);
        assertThat(event.getUserId()).isEqualTo(42L);
        assertThat(event.getDelta()).isEqualTo(1);
        assertThat(event.getFactEpoch()).isEqualTo(4L);
        verify(eventPublisher).publishEvent(any(CounterReactionCommittedEvent.class));
    }

    @Test
    void repeatedLikeDoesNotCreateOutboxEvent() {
        when(persistenceMapper.lockReactionEpochs("post", "7")).thenReturn(List.of(0L, 0L));
        when(reactionMapper.insertIgnore("post", "7", "like", 42L)).thenReturn(0);

        assertThat(service.setReaction("post", "7", "like", 42L, true)).isFalse();

        verify(outboxMapper, never()).insert(
                anyLong(), anyString(), any(), anyString(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
        verify(idGenerator, never()).nextId();
    }

    @Test
    void unlikeDeletesFactAndCreatesNegativeEventOnlyWhenChanged() throws Exception {
        when(persistenceMapper.lockReactionEpochs("post", "7")).thenReturn(List.of(9L, 9L));
        when(reactionMapper.delete("post", "7", "like", 42L)).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(456L);
        when(outboxMapper.insert(
                eq(456L), eq("counter_reaction"), eq(42L),
                eq("CounterReactionChanged"), anyString())).thenReturn(1);

        assertThat(service.setReaction("post", "7", "like", 42L, false)).isTrue();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxMapper).insert(
                eq(456L), eq("counter_reaction"), eq(42L),
                eq("CounterReactionChanged"), payload.capture());
        CounterEvent event = new ObjectMapper().readValue(payload.getValue(), CounterEvent.class);
        assertThat(event.getDelta()).isEqualTo(-1);
        assertThat(event.getFactEpoch()).isEqualTo(9L);
    }

    @Test
    void repeatedUnlikeDoesNotCreateEvent() {
        when(persistenceMapper.lockReactionEpochs("post", "7")).thenReturn(List.of(0L, 0L));
        when(reactionMapper.delete("post", "7", "like", 42L)).thenReturn(0);

        assertThat(service.setReaction("post", "7", "like", 42L, false)).isFalse();

        verify(outboxMapper, never()).insert(
                anyLong(), anyString(), any(), anyString(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void favoriteUsesFavoriteMetricIndex() throws Exception {
        when(persistenceMapper.lockReactionEpochs("post", "7")).thenReturn(List.of(2L, 2L));
        when(reactionMapper.insertIgnore("post", "7", "fav", 42L)).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(789L);
        when(outboxMapper.insert(
                eq(789L), eq("counter_reaction"), eq(42L),
                eq("CounterReactionChanged"), anyString())).thenReturn(1);

        assertThat(service.setReaction("post", "7", "fav", 42L, true)).isTrue();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxMapper).insert(
                eq(789L), eq("counter_reaction"), eq(42L),
                eq("CounterReactionChanged"), payload.capture());
        CounterEvent event = new ObjectMapper().readValue(payload.getValue(), CounterEvent.class);
        assertThat(event.getIdx()).isEqualTo(2);
        assertThat(event.getMetric()).isEqualTo("fav");
    }

    @Test
    void outboxFailureEscapesTransactionBoundary() {
        when(persistenceMapper.lockReactionEpochs("post", "7")).thenReturn(List.of(0L, 0L));
        when(reactionMapper.insertIgnore("post", "7", "like", 42L)).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(123L);
        doThrow(new IllegalStateException("outbox down"))
                .when(outboxMapper)
                .insert(eq(123L), eq("counter_reaction"), eq(42L),
                        eq("CounterReactionChanged"), anyString());

        assertThatThrownBy(() -> service.setReaction("post", "7", "like", 42L, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox down");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void missingOutboxInsertFailsBeforePublishingCommittedEvent() {
        when(persistenceMapper.lockReactionEpochs("post", "7")).thenReturn(List.of(0L, 0L));
        when(reactionMapper.insertIgnore("post", "7", "like", 42L)).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(123L);
        when(outboxMapper.insert(
                eq(123L), eq("counter_reaction"), eq(42L),
                eq("CounterReactionChanged"), anyString()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.setReaction("post", "7", "like", 42L, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Outbox");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void relationFailureCannotCreateAnOutboxEvent() {
        when(persistenceMapper.lockReactionEpochs("post", "7")).thenReturn(List.of(0L, 0L));
        doThrow(new IllegalStateException("relation down"))
                .when(reactionMapper)
                .insertIgnore("post", "7", "like", 42L);

        assertThatThrownBy(() -> service.setReaction("post", "7", "like", 42L, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("relation down");

        verify(outboxMapper, never()).insert(
                anyLong(), anyString(), any(), anyString(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void inconsistentSnapshotEpochsFailBeforeFactMutation() {
        when(persistenceMapper.lockReactionEpochs("post", "7")).thenReturn(List.of(3L, 4L));

        assertThatThrownBy(() -> service.setReaction("post", "7", "like", 42L, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("epoch");

        verify(reactionMapper, never()).insertIgnore(anyString(), anyString(), anyString(), anyLong());
    }
}
