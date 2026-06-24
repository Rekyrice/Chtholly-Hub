-- 将遗留表名/列名对齐 Chtholly Hub 命名（自 zhiguang 迁入库升级）

-- users.zg_id -> handle
ALTER TABLE users
    CHANGE COLUMN zg_id handle VARCHAR(64) NULL COMMENT '用户唯一标识 @handle';

ALTER TABLE users
    DROP INDEX uk_users_zg_id;

ALTER TABLE users
    ADD UNIQUE KEY uk_users_handle (handle);

-- know_posts -> posts
RENAME TABLE know_posts TO posts;

-- 外键约束名（若存在则重命名，忽略错误即可手动执行）
-- ALTER TABLE posts RENAME INDEX fk_know_posts_creator TO fk_posts_creator;
