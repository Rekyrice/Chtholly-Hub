package com.chtholly.relation.service;

import com.chtholly.relation.mapper.RelationMapper;
import com.chtholly.relation.processor.RelationEventProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/** Periodically reconciles existing relation projections from the following table. */
@Service
public class RelationCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(RelationCalibrationService.class);

    private final RelationMapper relationMapper;
    private final RelationEventProcessor processor;
    private final boolean enabled;
    private final int batchSize;
    private Long afterId;

    public RelationCalibrationService(RelationMapper relationMapper,
                                      RelationEventProcessor processor,
                                      @Value("${relation.calibration.enabled:true}") boolean enabled,
                                      @Value("${relation.calibration.batch-size:50}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("relation calibration batch size must be between 1 and 1000");
        }
        this.relationMapper = relationMapper;
        this.processor = processor;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    public void reconcilePair(long fromUserId, long toUserId) {
        processor.reconcile(fromUserId, toUserId);
    }

    @Scheduled(fixedDelayString = "${relation.calibration.fixed-delay:PT5M}")
    public synchronized void reconcileScheduled() {
        if (!enabled) {
            return;
        }
        List<RelationMapper.CalibrationCandidate> candidates =
                relationMapper.listCalibrationCandidatesAfter(afterId, batchSize);
        if (candidates.isEmpty()) {
            afterId = null;
            return;
        }
        for (RelationMapper.CalibrationCandidate candidate : candidates) {
            try {
                processor.reconcile(candidate.fromUserId(), candidate.toUserId());
            } catch (RuntimeException failure) {
                log.warn("Relation calibration failed for following ID {}: {}",
                        candidate.id(), failure.getMessage(), failure);
            } finally {
                afterId = candidate.id();
            }
        }
    }
}
