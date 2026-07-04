-- 珂朵莉 AI 评论标识与每日计数索引

ALTER TABLE comments
    ADD COLUMN is_chtholly TINYINT(1) NOT NULL DEFAULT 0 AFTER content;

CREATE INDEX idx_comments_chtholly_created
    ON comments (is_chtholly, created_at);

-- 珂朵莉 bot 账号（与站长 id=1 分离）
INSERT INTO users (id, nickname, avatar, bio, handle, created_at, updated_at)
VALUES (2, '珂朵莉', NULL, '仓库里的珂朵莉', 'chtholly', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    nickname = VALUES(nickname),
    bio = VALUES(bio),
    handle = VALUES(handle);
