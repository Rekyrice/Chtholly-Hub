-- V21__chtholly_bot_user.sql: 确保珂朵莉 AI 评论账号使用专用高 id

SET @chtholly_bot_id = 888888888888888888;

UPDATE users
SET handle = CONCAT('chtholly-legacy-', id)
WHERE handle = 'chtholly'
  AND id <> @chtholly_bot_id;

INSERT INTO users (id, nickname, avatar, bio, handle, created_at, updated_at)
VALUES (
    @chtholly_bot_id,
    '珂朵莉',
    NULL,
    '仓库里的珂朵莉',
    'chtholly',
    NOW(3),
    NOW(3)
)
ON DUPLICATE KEY UPDATE
    nickname = VALUES(nickname),
    bio = VALUES(bio),
    handle = VALUES(handle);
