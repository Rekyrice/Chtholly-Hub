package com.chtholly.post.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostOutboxProjectionProcessorTest {

    @Mock private PostProjectionReceiptMapper receiptMapper;
    @Mock private PostOutboxProjectionService projectionService;

    @Test
    void successfulProjectionAdvancesTheLockedCursorOnlyAfterTheReceipt() {
        when(receiptMapper.insertCursorIfAbsent(42L)).thenReturn(1);
        when(receiptMapper.lockCursor(42L)).thenReturn(100L);
        when(receiptMapper.insertReceipt(101L, 42L)).thenReturn(1);
        when(receiptMapper.advanceCursor(42L, 100L, 101L)).thenReturn(1);

        processor().process(101L, "PostPublished", 42L);

        InOrder order = inOrder(projectionService, receiptMapper);
        order.verify(receiptMapper).insertCursorIfAbsent(42L);
        order.verify(receiptMapper).lockCursor(42L);
        order.verify(projectionService).project("PostPublished", 42L);
        order.verify(receiptMapper).insertReceipt(101L, 42L);
        order.verify(receiptMapper).advanceCursor(42L, 100L, 101L);
    }

    @Test
    void repeatedEventWithDurableReceiptSkipsProjection() {
        when(receiptMapper.insertCursorIfAbsent(42L)).thenReturn(0);
        when(receiptMapper.lockCursor(42L)).thenReturn(102L);
        when(receiptMapper.countReceipt(101L)).thenReturn(1);

        processor().process(101L, "PostPublished", 42L);

        verifyNoInteractions(projectionService);
        verify(receiptMapper, never()).insertReceipt(101L, 42L);
        verify(receiptMapper, never()).advanceCursor(42L, 102L, 101L);
    }

    @Test
    void olderEventWithoutReceiptRepairsItsProjectionWithoutRewindingCursor() {
        when(receiptMapper.insertCursorIfAbsent(42L)).thenReturn(0);
        when(receiptMapper.lockCursor(42L)).thenReturn(102L);
        when(receiptMapper.countReceipt(101L)).thenReturn(0);
        when(receiptMapper.countOutboxEvent(101L)).thenReturn(1);
        when(receiptMapper.insertReceipt(101L, 42L)).thenReturn(1);

        processor().process(101L, "PostPublished", 42L);

        InOrder order = inOrder(projectionService, receiptMapper);
        order.verify(receiptMapper).insertCursorIfAbsent(42L);
        order.verify(receiptMapper).lockCursor(42L);
        order.verify(receiptMapper).countReceipt(101L);
        order.verify(projectionService).project("PostPublished", 42L);
        order.verify(receiptMapper).insertReceipt(101L, 42L);
        verify(receiptMapper, never()).advanceCursor(42L, 102L, 101L);
    }

    @Test
    void delayedDuplicateAfterParentCleanupSkipsProjectionAndReceiptIdempotently() {
        when(receiptMapper.insertCursorIfAbsent(42L)).thenReturn(0);
        when(receiptMapper.lockCursor(42L)).thenReturn(102L);
        when(receiptMapper.countReceipt(101L)).thenReturn(0);

        assertThatCode(() -> processor().process(101L, "PostPublished", 42L))
                .doesNotThrowAnyException();

        verify(receiptMapper).countOutboxEvent(101L);
        verifyNoInteractions(projectionService);
        verify(receiptMapper, never()).insertReceipt(101L, 42L);
        verify(receiptMapper, never()).advanceCursor(42L, 102L, 101L);
    }

    @Test
    void newerEventCannotUseTheCleanedDuplicateFastPath() {
        when(receiptMapper.insertCursorIfAbsent(42L)).thenReturn(0);
        when(receiptMapper.lockCursor(42L)).thenReturn(100L);
        when(receiptMapper.countReceipt(101L)).thenReturn(0);

        assertThatThrownBy(() -> processor().process(101L, "PostPublished", 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("receipt insert");

        verify(receiptMapper, never()).countOutboxEvent(101L);
        verify(projectionService).project("PostPublished", 42L);
        verify(receiptMapper, never()).advanceCursor(42L, 100L, 101L);
    }

    @Test
    void failedProjectionCannotCreateAReceiptOrAdvanceTheCursor() {
        when(receiptMapper.insertCursorIfAbsent(42L)).thenReturn(0);
        when(receiptMapper.lockCursor(42L)).thenReturn(100L);
        doThrow(new IllegalStateException("RAG unavailable"))
                .when(projectionService).project("PostPublished", 42L);

        assertThatThrownBy(() -> processor().process(101L, "PostPublished", 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RAG unavailable");

        verify(receiptMapper, never()).insertReceipt(101L, 42L);
        verify(receiptMapper, never()).advanceCursor(42L, 100L, 101L);
    }

    @Test
    void missingCursorAfterInitializationFailsClosed() {
        when(receiptMapper.insertCursorIfAbsent(42L)).thenReturn(0);
        when(receiptMapper.lockCursor(42L)).thenReturn(null);

        assertThatThrownBy(() -> processor().process(101L, "PostPublished", 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cursor");

        verifyNoInteractions(projectionService);
    }

    private PostOutboxProjectionProcessor processor() {
        return new PostOutboxProjectionProcessor(receiptMapper, projectionService);
    }
}
