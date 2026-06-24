-- posts 表增加 slug（博客友好 URL）

ALTER TABLE posts
    ADD COLUMN slug VARCHAR(128) NULL COMMENT 'URL 友好标识' AFTER title,
    ADD UNIQUE KEY uk_posts_slug (slug);
