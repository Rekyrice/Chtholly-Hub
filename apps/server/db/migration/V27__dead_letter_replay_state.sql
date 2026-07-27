-- Distinguish automatic retry, active manual replay and ambiguous broker outcomes.
ALTER TABLE dead_letter_messages
    MODIFY COLUMN status
        ENUM('PENDING', 'RETRYING', 'DEAD', 'REPLAYING', 'UNCERTAIN')
        NOT NULL DEFAULT 'PENDING';

SET @dead_letter_attempt_token_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'dead_letter_messages'
      AND column_name = 'replay_attempt_token'
);
SET @dead_letter_attempt_token_ddl = IF(
    @dead_letter_attempt_token_exists = 0,
    'ALTER TABLE dead_letter_messages ADD COLUMN replay_attempt_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER created_at',
    'ALTER TABLE dead_letter_messages MODIFY COLUMN replay_attempt_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL'
);
PREPARE dead_letter_attempt_token_statement
    FROM @dead_letter_attempt_token_ddl;
EXECUTE dead_letter_attempt_token_statement;
DEALLOCATE PREPARE dead_letter_attempt_token_statement;

SET @dead_letter_replay_started_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'dead_letter_messages'
      AND column_name = 'replay_started_at'
);
SET @dead_letter_replay_started_ddl = IF(
    @dead_letter_replay_started_exists = 0,
    'ALTER TABLE dead_letter_messages ADD COLUMN replay_started_at DATETIME(3) NULL AFTER replay_attempt_token',
    'ALTER TABLE dead_letter_messages MODIFY COLUMN replay_started_at DATETIME(3) NULL'
);
PREPARE dead_letter_replay_started_statement
    FROM @dead_letter_replay_started_ddl;
EXECUTE dead_letter_replay_started_statement;
DEALLOCATE PREPARE dead_letter_replay_started_statement;

SET @dead_letter_replay_deadline_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'dead_letter_messages'
      AND column_name = 'replay_deadline_at'
);
SET @dead_letter_replay_deadline_ddl = IF(
    @dead_letter_replay_deadline_exists = 0,
    'ALTER TABLE dead_letter_messages ADD COLUMN replay_deadline_at DATETIME(3) NULL AFTER replay_started_at',
    'ALTER TABLE dead_letter_messages MODIFY COLUMN replay_deadline_at DATETIME(3) NULL'
);
PREPARE dead_letter_replay_deadline_statement
    FROM @dead_letter_replay_deadline_ddl;
EXECUTE dead_letter_replay_deadline_statement;
DEALLOCATE PREPARE dead_letter_replay_deadline_statement;
