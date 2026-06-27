-- 用户角色与封禁状态
ALTER TABLE users
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER' AFTER tags_json,
    ADD COLUMN banned_at DATETIME NULL AFTER role;

-- 管理员操作审计
CREATE TABLE IF NOT EXISTS admin_audit_logs (
    id BIGINT NOT NULL PRIMARY KEY,
    admin_user_id BIGINT NOT NULL,
    action VARCHAR(50) NOT NULL,
    target_type VARCHAR(50) NULL,
    target_id BIGINT NULL,
    detail JSON NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_admin (admin_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
