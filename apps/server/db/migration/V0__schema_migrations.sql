-- 记录已执行的 migration 脚本（本地/部署增量迁移）
CREATE TABLE IF NOT EXISTS schema_migrations (
    version VARCHAR(64) NOT NULL,
    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
