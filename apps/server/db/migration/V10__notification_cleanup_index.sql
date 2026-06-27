-- 通知清理任务索引（已读 + 创建时间）
ALTER TABLE notifications ADD INDEX idx_cleanup (read_at, created_at);
