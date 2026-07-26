-- V25: durable like/favorite membership facts.

CREATE TABLE IF NOT EXISTS counter_reaction (
    entity_type VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    entity_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    metric VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (entity_type, entity_id, metric, user_id),
    KEY ix_counter_reaction_user_metric (user_id, metric, entity_type, entity_id),
    CONSTRAINT ck_counter_reaction_metric CHECK (metric IN ('like', 'fav'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
