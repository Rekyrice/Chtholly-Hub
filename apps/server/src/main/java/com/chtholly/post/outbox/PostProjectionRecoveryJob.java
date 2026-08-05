package com.chtholly.post.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Repairs durable post projections that remain incomplete after the primary delivery path. */
@Component
public class PostProjectionRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(PostProjectionRecoveryJob.class);

    private final PostProjectionReceiptMapper receiptMapper;
    private final PostOutboxProjectionProcessor processor;
    private final int batchSize;
    private long scanCursor;
    private long scanHighWatermark;

    public PostProjectionRecoveryJob(
            PostProjectionReceiptMapper receiptMapper,
            PostOutboxProjectionProcessor processor,
            @Value("${post.projection.local-replay.batch-size:100}") int batchSize) {
        this.receiptMapper = Objects.requireNonNull(receiptMapper, "receiptMapper");
        this.processor = Objects.requireNonNull(processor, "processor");
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException(
                    "post projection recovery batch size must be between 1 and 500");
        }
        this.batchSize = batchSize;
    }

    /** Runs one finite retry page; unsuccessful rows remain pending in MySQL. */
    @Scheduled(
            fixedDelayString = "${post.projection.local-replay.fixed-delay:PT5S}",
            initialDelayString = "${post.projection.local-replay.initial-delay:PT5S}")
    public synchronized void replayPending() {
        try {
            for (PostProjectionReceiptMapper.ReplayRow row : loadNextPage()) {
                try {
                    validate(row);
                    processor.process(row.id(), row.type(), row.postId());
                } catch (RuntimeException failure) {
                    log.error("Post projection event {} remains pending: {}",
                            row == null ? null : row.id(), failure.getMessage(), failure);
                }
            }
        } catch (RuntimeException failure) {
            log.error("Post projection recovery scan failed: {}", failure.getMessage(), failure);
        }
    }

    private List<PostProjectionReceiptMapper.ReplayRow> loadNextPage() {
        if (scanHighWatermark == 0L) {
            Long highWatermark = receiptMapper.findReplayHighWatermark();
            if (highWatermark == null || highWatermark < 0L) {
                throw new IllegalStateException("Post projection replay high watermark is invalid");
            }
            if (highWatermark == 0L) {
                return List.of();
            }
            scanHighWatermark = highWatermark;
        }

        long afterId = scanCursor;
        long throughId = scanHighWatermark;
        List<PostProjectionReceiptMapper.ReplayRow> rows =
                receiptMapper.listReplayPage(afterId, throughId, batchSize);
        if (rows == null) {
            throw new IllegalStateException("Post projection replay query returned no result");
        }
        if (rows.isEmpty()) {
            resetScanWindow();
            return List.of();
        }

        long previousId = afterId;
        for (PostProjectionReceiptMapper.ReplayRow row : rows) {
            if (row == null || row.id() <= previousId || row.id() > throughId) {
                throw new IllegalStateException(
                        "Post projection replay page is outside its ordered scan window");
            }
            previousId = row.id();
        }
        if (previousId >= throughId) {
            resetScanWindow();
        } else {
            scanCursor = previousId;
        }
        return rows;
    }

    private void resetScanWindow() {
        scanCursor = 0L;
        scanHighWatermark = 0L;
    }

    private static void validate(PostProjectionReceiptMapper.ReplayRow row) {
        if (row == null || row.id() <= 0 || row.postId() <= 0
                || !PostOutboxProjectionService.supports(row.type())) {
            throw new IllegalArgumentException("Post projection replay row is invalid");
        }
    }
}
