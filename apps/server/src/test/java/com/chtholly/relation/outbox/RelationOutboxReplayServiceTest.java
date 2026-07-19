package com.chtholly.relation.outbox;

import com.chtholly.relation.event.RelationEvent;
import com.chtholly.relation.processor.RelationEventProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelationOutboxReplayServiceTest {

    @Mock
    private OutboxMapper outboxMapper;

    @Mock
    private RelationEventProcessor processor;

    private RelationOutboxReplayService service;

    @BeforeEach
    void setUp() {
        service = new RelationOutboxReplayService(outboxMapper, processor, new ObjectMapper());
    }

    @Test
    void replayRangeProjectsInclusiveRowsInAscendingIdOrder() throws Exception {
        RelationEvent created = new RelationEvent("FollowCreated", 11L, 22L, 101L);
        RelationEvent canceled = new RelationEvent("FollowCanceled", 11L, 22L, null);
        when(outboxMapper.listRelationReplayRows(10L, 20L, 1001)).thenReturn(List.of(
                row(10L, created),
                row(20L, canceled)));

        assertThat(service.replayRange(10L, 20L)).isEqualTo(2);

        InOrder order = inOrder(processor);
        order.verify(processor).process(created);
        order.verify(processor).process(canceled);
    }

    @Test
    void replayIdRunsTerminalProjectionWithoutConsultingTransientConsumerGuard() throws Exception {
        RelationEvent event = new RelationEvent("FollowCreated", 11L, 22L, 101L);
        when(outboxMapper.findRelationReplayRow(42L)).thenReturn(row(42L, event));

        assertThat(service.replayId(42L)).isOne();

        verify(processor).process(event);
    }

    @Test
    void payloadTypeMismatchFailsBeforeProjection() throws Exception {
        RelationEvent payload = new RelationEvent("FollowCanceled", 11L, 22L, null);
        when(outboxMapper.findRelationReplayRow(42L)).thenReturn(new OutboxMapper.RelationReplayRow(
                42L, "FollowCreated", new ObjectMapper().writeValueAsString(payload)));

        assertThatThrownBy(() -> service.replayId(42L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");

        verifyNoInteractions(processor);
    }

    @Test
    void oversizedRangeIsRejectedWithoutPartialProjection() {
        List<OutboxMapper.RelationReplayRow> rows = LongStream.rangeClosed(1, 1001)
                .mapToObj(id -> new OutboxMapper.RelationReplayRow(
                        id, "FollowCanceled", "{\"type\":\"FollowCanceled\",\"fromUserId\":11,\"toUserId\":22}"))
                .toList();
        when(outboxMapper.listRelationReplayRows(1L, 2000L, 1001)).thenReturn(rows);

        assertThatThrownBy(() -> service.replayRange(1L, 2000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1000");

        verify(processor, never()).process(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingOrUnrelatedOutboxIdReturnsZero() {
        when(outboxMapper.findRelationReplayRow(42L)).thenReturn(null);

        assertThat(service.replayId(42L)).isZero();

        verifyNoInteractions(processor);
    }

    private OutboxMapper.RelationReplayRow row(long id, RelationEvent event) throws Exception {
        return new OutboxMapper.RelationReplayRow(
                id, event.type(), new ObjectMapper().writeValueAsString(event));
    }
}
