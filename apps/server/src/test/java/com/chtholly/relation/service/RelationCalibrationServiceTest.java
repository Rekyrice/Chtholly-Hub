package com.chtholly.relation.service;

import com.chtholly.relation.mapper.RelationMapper;
import com.chtholly.relation.processor.RelationEventProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelationCalibrationServiceTest {

    @Mock
    private RelationMapper relationMapper;

    @Mock
    private RelationEventProcessor processor;

    private RelationCalibrationService service;

    @BeforeEach
    void setUp() {
        service = new RelationCalibrationService(relationMapper, processor, true, 2);
    }

    @Test
    void disabledCalibrationDoesNotScanAuthority() {
        RelationCalibrationService disabled = new RelationCalibrationService(
                relationMapper, processor, false, 2);

        disabled.reconcileScheduled();

        verifyNoInteractions(relationMapper, processor);
    }

    @Test
    void advancesBoundedCursorAndWrapsAfterEmptyPage() {
        RelationMapper.CalibrationCandidate first = new RelationMapper.CalibrationCandidate(10L, 11L, 21L);
        RelationMapper.CalibrationCandidate second = new RelationMapper.CalibrationCandidate(20L, 12L, 22L);
        when(relationMapper.listCalibrationCandidatesAfter(null, 2))
                .thenReturn(List.of(first, second), List.of());
        when(relationMapper.listCalibrationCandidatesAfter(20L, 2)).thenReturn(List.of());

        service.reconcileScheduled();
        service.reconcileScheduled();
        service.reconcileScheduled();

        InOrder order = inOrder(relationMapper, processor);
        order.verify(relationMapper).listCalibrationCandidatesAfter(null, 2);
        order.verify(processor).reconcile(11L, 21L);
        order.verify(processor).reconcile(12L, 22L);
        order.verify(relationMapper).listCalibrationCandidatesAfter(20L, 2);
        order.verify(relationMapper).listCalibrationCandidatesAfter(null, 2);
    }

    @Test
    void oneBrokenPairDoesNotBlockLaterCandidates() {
        RelationMapper.CalibrationCandidate first = new RelationMapper.CalibrationCandidate(10L, 11L, 21L);
        RelationMapper.CalibrationCandidate second = new RelationMapper.CalibrationCandidate(20L, 12L, 22L);
        when(relationMapper.listCalibrationCandidatesAfter(null, 2)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("broken pair")).when(processor).reconcile(11L, 21L);

        service.reconcileScheduled();

        verify(processor).reconcile(12L, 22L);
    }

    @Test
    void reconcilePairDelegatesWithoutConstructingSyntheticEvent() {
        service.reconcilePair(11L, 22L);

        verify(processor).reconcile(11L, 22L);
        verify(processor, never()).process(org.mockito.ArgumentMatchers.any());
    }
}
