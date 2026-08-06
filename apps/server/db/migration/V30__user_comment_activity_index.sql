-- V30: stable lookup for one user's visible comment activity.

SET @comment_activity_index_rows = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'comments'
      AND index_name = 'ix_comments_user_deleted_ct'
);
SET @comment_activity_index_valid = (
    SELECT IF(
        COUNT(*) = 4
            AND GROUP_CONCAT(column_name ORDER BY seq_in_index)
                = 'user_id,deleted_at,created_at,id'
            AND SUM(sub_part IS NOT NULL) = 0
            AND MIN(is_visible) = 'YES'
            AND MIN(index_type) = 'BTREE'
            AND MIN(non_unique) = 1,
        1,
        0
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'comments'
      AND index_name = 'ix_comments_user_deleted_ct'
);
SET @comment_activity_index_ddl = IF(
    @comment_activity_index_valid = 1,
    'SELECT 1',
    IF(
        @comment_activity_index_rows > 0,
        'ALTER TABLE comments DROP INDEX ix_comments_user_deleted_ct, ADD KEY ix_comments_user_deleted_ct (user_id, deleted_at, created_at, id)',
        'ALTER TABLE comments ADD KEY ix_comments_user_deleted_ct (user_id, deleted_at, created_at, id)'
    )
);
PREPARE comment_activity_index_statement FROM @comment_activity_index_ddl;
EXECUTE comment_activity_index_statement;
DEALLOCATE PREPARE comment_activity_index_statement;
