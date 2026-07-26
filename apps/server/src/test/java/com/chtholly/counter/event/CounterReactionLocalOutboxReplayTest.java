package com.chtholly.counter.event;

import com.chtholly.relation.outbox.OutboxMapper;
import com.chtholly.counter.mapper.CounterReactionKey;
import com.chtholly.counter.service.impl.CounterReactionProjectionStore.ProjectionBatchException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CounterReactionLocalOutboxReplayTest {

    @Mock
    private OutboxMapper outboxMapper;
    @Mock
    private CounterReactionEventProcessor processor;

    private ObjectMapper objectMapper;
    private CounterReactionLocalOutboxReplay replay;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        replay = new CounterReactionLocalOutboxReplay(
                outboxMapper, objectMapper, processor, 100);
    }

    @Test
    void replaysOneBoundedPendingOutboxBatchThroughTheSharedCore() throws Exception {
        CounterEvent like = event("41", "7", "like", 1);
        CounterEvent favorite = event("42", "8", "fav", 1);
        when(outboxMapper.findCounterReactionReplayHighWatermark()).thenReturn(42L);
        when(outboxMapper.listCounterReactionReplayPage(0L, 42L, 100)).thenReturn(List.of(
                row(41L, like, true),
                row(42L, favorite, true)));

        replay.replayPending();

        verify(processor).process(List.of(like, favorite));
    }

    @Test
    void failedRowCannotStarveLaterPagesAndIsRetriedInTheNextFiniteScan()
            throws Exception {
        CounterEvent failed = event("41", "7", "like", 1);
        CounterEvent valid = event("42", "8", "fav", 1);
        replay = new CounterReactionLocalOutboxReplay(
                outboxMapper, objectMapper, processor, 1);
        when(outboxMapper.findCounterReactionReplayHighWatermark())
                .thenReturn(42L, 42L);
        when(outboxMapper.listCounterReactionReplayPage(0L, 42L, 1))
                .thenReturn(
                        List.of(row(41L, failed, true)),
                        List.of(row(41L, failed, true)));
        when(outboxMapper.listCounterReactionReplayPage(41L, 42L, 1))
                .thenReturn(List.of(row(42L, valid, true)));
        doThrow(new IllegalStateException("redis down"))
                .doThrow(new IllegalStateException("redis down"))
                .when(processor).process(List.of(failed));

        replay.replayPending();
        replay.replayPending();
        replay.replayPending();

        InOrder order = inOrder(processor);
        order.verify(processor).process(List.of(failed));
        order.verify(processor).process(List.of(valid));
        order.verify(processor).process(List.of(failed));
        verify(processor, times(2)).process(List.of(failed));
        verify(outboxMapper, times(2)).findCounterReactionReplayHighWatermark();
        verify(outboxMapper).listCounterReactionReplayPage(41L, 42L, 1);
    }

    @Test
    void keyedProjectionFailureKeepsAllHealthyPagePeersInOneBatch()
            throws Exception {
        CounterEvent failed = event("41", "7", "like", 1);
        CounterEvent validFavorite = event("42", "8", "fav", 1);
        CounterEvent validLike = event("43", "9", "like", 1);
        CounterEvent anotherFavorite = event("44", "10", "fav", 1);
        List<CounterEvent> all =
                List.of(failed, validFavorite, validLike, anotherFavorite);
        List<CounterEvent> healthy =
                List.of(validFavorite, validLike, anotherFavorite);
        when(outboxMapper.findCounterReactionReplayHighWatermark()).thenReturn(44L);
        when(outboxMapper.listCounterReactionReplayPage(0L, 44L, 100))
                .thenReturn(List.of(
                        row(41L, failed, true),
                        row(42L, validFavorite, true),
                        row(43L, validLike, true),
                        row(44L, anotherFavorite, true)));
        doThrow(new ProjectionBatchException(java.util.Set.of(
                new CounterReactionKey("post", "7", "like", 42L))))
                .when(processor).process(all);

        replay.replayPending();

        InOrder order = inOrder(processor);
        order.verify(processor).process(all);
        order.verify(processor).process(healthy);
    }

    @Test
    void infrastructureFailureFailsTheWholePageWithoutRetryAmplification()
            throws Exception {
        List<CounterEvent> events = events(4);
        when(outboxMapper.findCounterReactionReplayHighWatermark()).thenReturn(103L);
        when(outboxMapper.listCounterReactionReplayPage(0L, 103L, 100))
                .thenReturn(events.stream()
                        .map(event -> {
                            try {
                                return row(
                                        Long.parseLong(event.getEventId()),
                                        event,
                                        true);
                            } catch (Exception exception) {
                                throw new IllegalStateException(exception);
                            }
                        })
                        .toList());
        doThrow(new DataAccessResourceFailureException("mysql unavailable"))
                .when(processor).process(events);

        replay.replayPending();

        verify(processor).process(events);
    }

    @Test
    void unattributedProcessorFailureIsNotAmplified()
            throws Exception {
        List<CounterEvent> events = events(32);
        when(outboxMapper.findCounterReactionReplayHighWatermark()).thenReturn(131L);
        when(outboxMapper.listCounterReactionReplayPage(0L, 131L, 100))
                .thenReturn(events.stream()
                        .map(event -> {
                            try {
                                return row(
                                        Long.parseLong(event.getEventId()),
                                        event,
                                        true);
                            } catch (Exception exception) {
                                throw new IllegalStateException(exception);
                            }
                        })
                        .toList());
        doThrow(new IllegalStateException("unattributed processor failure"))
                .when(processor).process(events);

        replay.replayPending();

        verify(processor).process(events);
    }

    @Test
    void poisonedPayloadDoesNotBlockLaterValidRows() throws Exception {
        CounterEvent poisoned = event("42", "7", "like", 1);
        CounterEvent valid = event("43", "8", "like", 1);
        when(outboxMapper.findCounterReactionReplayHighWatermark()).thenReturn(43L);
        when(outboxMapper.listCounterReactionReplayPage(0L, 43L, 100))
                .thenReturn(List.of(
                        row(41L, poisoned, true),
                        row(43L, valid, true)));

        replay.replayPending();

        verify(processor).process(List.of(valid));
    }

    @Test
    void semanticallyInvalidReactionDoesNotPoisonItsValidPagePeer() throws Exception {
        CounterEvent poisoned = event("41", "7", "like", 1);
        poisoned.setIdx(2);
        CounterEvent valid = event("42", "8", "fav", 1);
        when(outboxMapper.findCounterReactionReplayHighWatermark()).thenReturn(42L);
        when(outboxMapper.listCounterReactionReplayPage(0L, 42L, 100))
                .thenReturn(List.of(
                        row(41L, poisoned, true),
                        row(42L, valid, true)));

        replay.replayPending();

        verify(processor).process(List.of(valid));
    }

    @Test
    void jsonNullPayloadDoesNotPoisonItsValidPagePeer() throws Exception {
        CounterEvent valid = event("42", "8", "fav", 1);
        when(outboxMapper.findCounterReactionReplayHighWatermark()).thenReturn(42L);
        when(outboxMapper.listCounterReactionReplayPage(0L, 42L, 100))
                .thenReturn(List.of(
                        new OutboxMapper.ReactionReplayRow(
                                41L, "CounterReactionChanged", "null", true),
                        row(42L, valid, true)));

        replay.replayPending();

        verify(processor).process(List.of(valid));
    }

    @Test
    void emptyPendingPageEndsTheCurrentPassWithoutScanningTheWindowTwice() {
        when(outboxMapper.findCounterReactionReplayHighWatermark()).thenReturn(42L);
        when(outboxMapper.listCounterReactionReplayPage(0L, 42L, 100))
                .thenReturn(List.of());

        replay.replayPending();

        verify(outboxMapper).findCounterReactionReplayHighWatermark();
        verify(outboxMapper).listCounterReactionReplayPage(0L, 42L, 100);
    }

    @Test
    void completedRowsAreFilteredBeforePagingPendingRows()
            throws Exception {
        CounterEvent pending = event("42", "8", "fav", 1);
        when(outboxMapper.findCounterReactionReplayHighWatermark()).thenReturn(42L);
        when(outboxMapper.listCounterReactionReplayPage(0L, 42L, 100))
                .thenReturn(List.of(row(42L, pending, true)));

        replay.replayPending();

        verify(processor).process(List.of(pending));
        verify(outboxMapper).listCounterReactionReplayPage(0L, 42L, 100);
    }

    @Test
    void replayQueryIsCursorBoundedAndGivesFreshCommitsTimeToPublish() throws Exception {
        String source = new ClassPathResource("mapper/OutboxMapper.xml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(source)
                .contains("o.id &gt; #{afterId}")
                .contains("o.id &lt;= #{throughId}")
                .contains("MAX(id)")
                .contains("o.created_at &lt;= TIMESTAMPADD(SECOND, -5, NOW(3))")
                .contains("AND (i.event_id IS NULL")
                .contains("OR (i.side_effects_published_at IS NULL")
                .contains("i.applied_at &lt;= TIMESTAMPADD(SECOND, -5, NOW(3))");
    }

    private OutboxMapper.ReactionReplayRow row(
            long id,
            CounterEvent event,
            boolean pending) throws Exception {
        return new OutboxMapper.ReactionReplayRow(
                id,
                "CounterReactionChanged",
                objectMapper.writeValueAsString(event),
                pending);
    }

    private static CounterEvent event(
            String eventId,
            String entityId,
            String metric,
            int delta) {
        return CounterEvent.of(
                eventId,
                "post",
                entityId,
                metric,
                "like".equals(metric) ? 1 : 2,
                42L,
                delta);
    }

    private static List<CounterEvent> events(int count) {
        List<CounterEvent> events = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long id = 100L + index;
            events.add(event(
                    Long.toString(id),
                    Long.toString(1000L + index),
                    index % 2 == 0 ? "like" : "fav",
                    1));
        }
        return List.copyOf(events);
    }
}
