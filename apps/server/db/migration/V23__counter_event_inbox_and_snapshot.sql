-- V23: durable counter-event idempotency and convergent display snapshots.

CREATE TABLE IF NOT EXISTS counter_event_inbox (
    event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_type VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    entity_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    metric VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    delta INT NOT NULL,
    user_id BIGINT NOT NULL,
    fact_epoch BIGINT UNSIGNED NOT NULL,
    applied_at DATETIME(3) NOT NULL,
    PRIMARY KEY (event_id),
    KEY ix_counter_inbox_entity (entity_type, entity_id, metric, applied_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS counter_snapshot (
    entity_type VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    entity_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    metric VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    count_value BIGINT NOT NULL,
    fact_epoch BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (entity_type, entity_id, metric),
    KEY ix_counter_snapshot_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
