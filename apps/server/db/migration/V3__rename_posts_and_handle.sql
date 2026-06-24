-- 遗留库升级：用户 handle 列与 posts 表命名对齐

-- handle 列（自旧用户标识列重命名）
ALTER TABLE users
    CHANGE COLUMN zg_id handle VARCHAR(64) NULL COMMENT '用户唯一标识 @handle';

ALTER TABLE users
    DROP INDEX uk_users_zg_id;

ALTER TABLE users
    ADD UNIQUE KEY uk_users_handle (handle);

-- posts 表（自旧帖子表重命名）
RENAME TABLE know_posts TO posts;
