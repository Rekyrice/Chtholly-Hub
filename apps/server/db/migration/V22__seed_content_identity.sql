-- V22__seed_content_identity.sql: immutable seed-key to entity identity mapping.

CREATE TABLE IF NOT EXISTS seed_content_identity (
    namespace VARCHAR(64) NOT NULL,
    entity_type VARCHAR(16) NOT NULL,
    seed_key VARCHAR(128) NOT NULL,
    entity_id BIGINT UNSIGNED NOT NULL,
    pack_version VARCHAR(64) NOT NULL,
    content_hash CHAR(64) NULL,
    metadata JSON NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (namespace, entity_type, seed_key),
    UNIQUE KEY uk_seed_identity_entity (entity_type, entity_id),
    KEY ix_seed_identity_version (pack_version, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
