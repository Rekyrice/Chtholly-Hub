-- Phase A 种子数据：站长 Rekyrice + 3 篇已发布 Markdown 帖子
-- 执行前确认 OSS 已上传对应 Markdown（路径 post/，Bucket chtholly-hub-dev，需公共读）
--
-- PowerShell（务必指定 UTF-8，否则中文会双重编码乱码）:
--   docker cp apps/server/db/seed/phase_a_seed.sql mysql:/tmp/phase_a_seed.sql
--   docker exec -i -e MYSQL_PWD='你的密码' mysql mysql -uroot --default-character-set=utf8mb4 chtholly -e "source /tmp/phase_a_seed.sql"

INSERT INTO users (id, nickname, avatar, bio, handle, created_at, updated_at)
VALUES (1, 'Rekyrice', NULL, 'Rekyrice · 动漫博客', 'rekyrice', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    nickname = VALUES(nickname),
    bio = VALUES(bio),
    handle = VALUES(handle);

INSERT INTO posts (
    id, tags, title, slug, description, content_url, content_object_key,
    creator_id, type, visible, status, publish_time, create_time, update_time
)
VALUES
(
    1001,
    '["动漫","推荐"]',
    '欢迎来到 Chtholly Hub',
    'welcome-chtholly-hub',
    '个人动漫博客上线',
    'https://chtholly-hub-dev.oss-cn-beijing.aliyuncs.com/post/welcome.md',
    'post/welcome.md',
    1,
    'image_text',
    'public',
    'published',
    NOW(), NOW(), NOW()
),
(
    1002,
    '["番剧"]',
    '2026 冬季追番清单',
    '2026-winter-anime-list',
    '冬季番推荐',
    'https://chtholly-hub-dev.oss-cn-beijing.aliyuncs.com/post/winter-2026.md',
    'post/winter-2026.md',
    1,
    'image_text',
    'public',
    'published',
    NOW(), NOW(), NOW()
),
(
    1003,
    '["珂朵莉"]',
    '为什么叫 Chtholly',
    'why-chtholly',
    '项目命名由来',
    'https://chtholly-hub-dev.oss-cn-beijing.aliyuncs.com/post/why-chtholly.md',
    'post/why-chtholly.md',
    1,
    'image_text',
    'public',
    'published',
    NOW(), NOW(), NOW()
)
ON DUPLICATE KEY UPDATE
    title = VALUES(title),
    slug = VALUES(slug),
    description = VALUES(description),
    tags = VALUES(tags),
    content_url = VALUES(content_url),
    content_object_key = VALUES(content_object_key),
    status = VALUES(status),
    visible = VALUES(visible),
    publish_time = VALUES(publish_time),
    update_time = NOW();
