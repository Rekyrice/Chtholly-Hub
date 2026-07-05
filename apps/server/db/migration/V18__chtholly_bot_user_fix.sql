-- 珂朵莉 bot 使用专用 id，避免与早期注册用户 id=2 冲突；并修复 UTF-8 昵称

SET @bot_id = 888888888888888888;

INSERT INTO users (id, nickname, avatar, bio, handle, created_at, updated_at)
VALUES (@bot_id, '珂朵莉', NULL, '仓库里的珂朵莉', 'chtholly', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    nickname = VALUES(nickname),
    bio = VALUES(bio);

UPDATE comments
SET user_id = @bot_id
WHERE is_chtholly = 1
  AND user_id <> @bot_id;
