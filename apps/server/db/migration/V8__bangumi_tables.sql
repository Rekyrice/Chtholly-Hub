-- Bangumi 番剧元数据本地缓存（Agent bangumi_search 使用）
CREATE TABLE IF NOT EXISTS bangumi_subjects (
    id BIGINT UNSIGNED NOT NULL COMMENT 'Bangumi subject id',
    type TINYINT NOT NULL DEFAULT 2 COMMENT '1书 2动画 3音乐 4游戏 6三次元',
    name VARCHAR(255) NOT NULL,
    name_cn VARCHAR(255) NULL,
    summary TEXT NULL,
    nsfw TINYINT(1) NOT NULL DEFAULT 0,
    air_date DATE NULL,
    score DECIMAL(4, 2) NULL,
    rank INT NULL,
    eps_count INT NULL,
    raw_json JSON NULL,
    synced_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    FULLTEXT KEY ft_bangumi_name (name, name_cn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS bangumi_episodes (
    id BIGINT UNSIGNED NOT NULL COMMENT 'Bangumi episode id',
    subject_id BIGINT UNSIGNED NOT NULL,
    ep INT NULL,
    sort_num INT NOT NULL DEFAULT 0,
    name VARCHAR(255) NULL,
    air_date DATE NULL,
    synced_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY ix_bgm_ep_subject (subject_id),
    CONSTRAINT fk_bgm_ep_subject FOREIGN KEY (subject_id) REFERENCES bangumi_subjects (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS bangumi_sync_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    subject_id BIGINT UNSIGNED NOT NULL,
    action VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY ix_bgm_sync_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
