-- V28: per-post ordering cursor and durable completion receipts for replayable projections.

CREATE TABLE IF NOT EXISTS post_projection_cursor (
    post_id BIGINT UNSIGNED NOT NULL,
    last_event_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS post_projection_receipt (
    event_id BIGINT UNSIGNED NOT NULL,
    post_id BIGINT UNSIGNED NOT NULL,
    completed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (event_id),
    KEY ix_post_projection_receipt_post (post_id, event_id),
    KEY ix_post_projection_receipt_completed (completed_at),
    CONSTRAINT fk_post_projection_receipt_outbox
        FOREIGN KEY (event_id) REFERENCES outbox(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
