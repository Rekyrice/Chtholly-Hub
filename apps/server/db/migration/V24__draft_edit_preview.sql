-- V24: synchronous, explicitly approved draft-edit previews.

CREATE TABLE IF NOT EXISTS draft_edit_preview (
    id BIGINT UNSIGNED NOT NULL,
    owner_id BIGINT UNSIGNED NOT NULL,
    draft_id BIGINT UNSIGNED NOT NULL,
    skill_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    skill_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    base_content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_content MEDIUMTEXT NOT NULL,
    candidate_content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preview_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    decided_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY ix_draft_edit_owner_draft (owner_id, draft_id, created_at),
    KEY ix_draft_edit_expiry (status, expires_at),
    CONSTRAINT fk_draft_edit_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    CONSTRAINT fk_draft_edit_draft FOREIGN KEY (draft_id) REFERENCES posts(id),
    CONSTRAINT ck_draft_edit_status CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
