package com.chtholly.counter.event;

import com.chtholly.counter.mapper.CounterPersistenceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CounterReactionSideEffectReceiptServiceTest {

    @Mock
    private CounterPersistenceMapper persistenceMapper;

    @Test
    void serializesPublicationAndPersistsTheReceiptWhileHoldingTheInboxLock() {
        Runnable publication = org.mockito.Mockito.mock(Runnable.class);
        when(persistenceMapper.lockReactionSideEffectPublication("41")).thenReturn(0);
        when(persistenceMapper.markReactionSideEffectsPublished("41")).thenReturn(1);
        CounterReactionSideEffectReceiptService service =
                new CounterReactionSideEffectReceiptService(persistenceMapper);

        assertThat(service.publishIfPending("41", publication)).isTrue();

        var order = inOrder(persistenceMapper, publication);
        order.verify(persistenceMapper).lockReactionSideEffectPublication("41");
        order.verify(publication).run();
        order.verify(persistenceMapper).markReactionSideEffectsPublished("41");
    }

    @Test
    void skipsAnAlreadyPublishedReplayWithoutCallingListenersAgain() {
        Runnable publication = org.mockito.Mockito.mock(Runnable.class);
        when(persistenceMapper.lockReactionSideEffectPublication("41")).thenReturn(1);
        CounterReactionSideEffectReceiptService service =
                new CounterReactionSideEffectReceiptService(persistenceMapper);

        assertThat(service.publishIfPending("41", publication)).isFalse();

        verify(publication, never()).run();
        verify(persistenceMapper, never()).markReactionSideEffectsPublished("41");
    }

    @Test
    void rejectsAMissingInboxRowInsteadOfPublishingWithoutAClaim() {
        Runnable publication = org.mockito.Mockito.mock(Runnable.class);
        when(persistenceMapper.lockReactionSideEffectPublication("42")).thenReturn(null);
        CounterReactionSideEffectReceiptService service =
                new CounterReactionSideEffectReceiptService(persistenceMapper);

        assertThatThrownBy(() -> service.publishIfPending("42", publication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("receipt");

        verify(publication, never()).run();
    }

    @Test
    void listenerFailureLeavesTheReceiptPendingForReplay() {
        Runnable publication = org.mockito.Mockito.mock(Runnable.class);
        when(persistenceMapper.lockReactionSideEffectPublication("43")).thenReturn(0);
        org.mockito.Mockito.doThrow(new IllegalStateException("listener down"))
                .when(publication).run();
        CounterReactionSideEffectReceiptService service =
                new CounterReactionSideEffectReceiptService(persistenceMapper);

        assertThatThrownBy(() -> service.publishIfPending("43", publication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("listener down");

        verify(persistenceMapper, never()).markReactionSideEffectsPublished("43");
    }

    @Test
    void rejectsAReceiptUpdateThatDidNotPersist() {
        Runnable publication = org.mockito.Mockito.mock(Runnable.class);
        when(persistenceMapper.lockReactionSideEffectPublication("44")).thenReturn(0);
        when(persistenceMapper.markReactionSideEffectsPublished("44")).thenReturn(0);
        CounterReactionSideEffectReceiptService service =
                new CounterReactionSideEffectReceiptService(persistenceMapper);

        assertThatThrownBy(() -> service.publishIfPending("44", publication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("receipt");
    }

    @Test
    void receiptOwnsAnIndependentTransaction() throws Exception {
        Method method = CounterReactionSideEffectReceiptService.class.getMethod(
                "publishIfPending", String.class, Runnable.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
