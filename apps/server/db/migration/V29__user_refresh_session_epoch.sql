-- Make MySQL the sole authority for user-wide refresh-session invalidation.
SET @refresh_session_epoch_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'users'
      AND column_name = 'refresh_session_epoch'
);
SET @refresh_session_epoch_ddl = IF(
    @refresh_session_epoch_exists = 0,
    'ALTER TABLE users ADD COLUMN refresh_session_epoch BIGINT NOT NULL DEFAULT 1 AFTER password_hash',
    'ALTER TABLE users MODIFY COLUMN refresh_session_epoch BIGINT NOT NULL DEFAULT 1'
);
PREPARE refresh_session_epoch_statement FROM @refresh_session_epoch_ddl;
EXECUTE refresh_session_epoch_statement;
DEALLOCATE PREPARE refresh_session_epoch_statement;
