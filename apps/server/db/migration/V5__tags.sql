-- 标签表：与 posts.tags JSON 双写，usage_count 由 Post 发布/删除/改标签维护

CREATE TABLE IF NOT EXISTS tags (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL COMMENT '展示名',
    slug VARCHAR(128) NOT NULL COMMENT 'URL 标识，可与 name 相同',
    creator_id BIGINT UNSIGNED NULL COMMENT '创建者（可选）',
    usage_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已发布帖子引用次数',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tags_name (name),
    UNIQUE KEY uk_tags_slug (slug),
    KEY ix_tags_usage (usage_count DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Phase A 种子帖已有标签的回填
INSERT INTO tags (name, slug, usage_count) VALUES
    ('动漫', '动漫', 1),
    ('推荐', '推荐', 1),
    ('番剧', '番剧', 1),
    ('珂朵莉', '珂朵莉', 1)
ON DUPLICATE KEY UPDATE
    usage_count = GREATEST(usage_count, VALUES(usage_count)),
    updated_at = NOW();
