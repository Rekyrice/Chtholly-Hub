CREATE TABLE IF NOT EXISTS bangumi_recommendations (
    bangumi_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(255) NOT NULL,
    title_cn VARCHAR(255) NULL,
    cover_url TEXT NULL,
    score DECIMAL(4, 2) NULL,
    chtholly_review TEXT NOT NULL,
    tags_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (bangumi_id),
    KEY ix_bgm_rec_score (score),
    KEY ix_bgm_rec_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS seed_data (
    seed_key VARCHAR(64) NOT NULL,
    summary_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (seed_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
