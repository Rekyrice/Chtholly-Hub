package com.chtholly.post.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostProjectionRecoveryJobTest {

    @Mock private PostProjectionReceiptMapper receiptMapper;
    @Mock private PostOutboxProjectionProcessor processor;

    private PostProjectionRecoveryJob replay;

    @BeforeEach
    void setUp() {
        replay = new PostProjectionRecoveryJob(receiptMapper, processor, 100);
    }

    @Test
    void replaysOneBoundedPendingPageThroughSharedProcessor() {
        when(receiptMapper.findReplayHighWatermark()).thenReturn(102L);
        when(receiptMapper.listReplayPage(0L, 102L, 100)).thenReturn(List.of(
                row(101L, "PostPublished", 41L),
                row(102L, "PostVisibilityChanged", 42L)));

        replay.replayPending();

        InOrder order = inOrder(processor);
        order.verify(processor).process(101L, "PostPublished", 41L);
        order.verify(processor).process(102L, "PostVisibilityChanged", 42L);
    }

    @Test
    void failedRowDoesNotStarveLaterRowsAndRetriesInNextFiniteScan() {
        replay = new PostProjectionRecoveryJob(receiptMapper, processor, 1);
        when(receiptMapper.findReplayHighWatermark()).thenReturn(102L, 102L);
        when(receiptMapper.listReplayPage(0L, 102L, 1)).thenReturn(
                List.of(row(101L, "PostPublished", 41L)),
                List.of(row(101L, "PostPublished", 41L)));
        when(receiptMapper.listReplayPage(101L, 102L, 1)).thenReturn(
                List.of(row(102L, "PostDeleted", 42L)));
        doThrow(new IllegalStateException("Elasticsearch unavailable"))
                .doThrow(new IllegalStateException("Elasticsearch unavailable"))
                .when(processor).process(101L, "PostPublished", 41L);

        replay.replayPending();
        replay.replayPending();
        replay.replayPending();

        InOrder order = inOrder(processor);
        order.verify(processor).process(101L, "PostPublished", 41L);
        order.verify(processor).process(102L, "PostDeleted", 42L);
        order.verify(processor).process(101L, "PostPublished", 41L);
        verify(processor, times(2)).process(101L, "PostPublished", 41L);
    }

    @Test
    void invalidRowDoesNotBlockItsValidPeer() {
        when(receiptMapper.findReplayHighWatermark()).thenReturn(102L);
        when(receiptMapper.listReplayPage(0L, 102L, 100)).thenReturn(List.of(
                row(101L, "PostPublished", 0L),
                row(102L, "PostDeleted", 42L)));

        replay.replayPending();

        verify(processor).process(102L, "PostDeleted", 42L);
    }

    private static PostProjectionReceiptMapper.ReplayRow row(
            long id, String type, long postId) {
        return new PostProjectionReceiptMapper.ReplayRow(id, type, postId);
    }
}
