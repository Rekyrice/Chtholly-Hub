-- V26: durable receipt for reaction side effects replayed from the existing Outbox.

SET @counter_reaction_receipt_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'counter_event_inbox'
      AND column_name = 'side_effects_published_at'
);
SET @counter_reaction_receipt_ddl = IF(
    @counter_reaction_receipt_column_exists = 0,
    'ALTER TABLE counter_event_inbox ADD COLUMN side_effects_published_at DATETIME(3) NULL AFTER applied_at',
    'SELECT 1'
);
PREPARE counter_reaction_receipt_statement FROM @counter_reaction_receipt_ddl;
EXECUTE counter_reaction_receipt_statement;
DEALLOCATE PREPARE counter_reaction_receipt_statement;

SET @counter_reaction_replay_index_rows = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'outbox'
      AND index_name = 'ix_outbox_reaction_replay'
);
SET @counter_reaction_replay_index_valid = (
    SELECT IF(
        COUNT(*) = 3
            AND GROUP_CONCAT(column_name ORDER BY seq_in_index)
                = 'aggregate_type,type,id'
            AND SUM(sub_part IS NOT NULL) = 0
            AND MIN(is_visible) = 'YES'
            AND MIN(index_type) = 'BTREE'
            AND MIN(non_unique) = 1,
        1,
        0
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'outbox'
      AND index_name = 'ix_outbox_reaction_replay'
);
SET @counter_reaction_replay_index_drop_ddl = IF(
    @counter_reaction_replay_index_rows > 0
        AND @counter_reaction_replay_index_valid = 0,
    'ALTER TABLE outbox DROP INDEX ix_outbox_reaction_replay',
    'SELECT 1'
);
PREPARE counter_reaction_replay_index_drop_statement
    FROM @counter_reaction_replay_index_drop_ddl;
EXECUTE counter_reaction_replay_index_drop_statement;
DEALLOCATE PREPARE counter_reaction_replay_index_drop_statement;

SET @counter_reaction_replay_index_ddl = IF(
    @counter_reaction_replay_index_valid = 0,
    'ALTER TABLE outbox ADD KEY ix_outbox_reaction_replay (aggregate_type, type, id)',
    'SELECT 1'
);
PREPARE counter_reaction_replay_index_statement
    FROM @counter_reaction_replay_index_ddl;
EXECUTE counter_reaction_replay_index_statement;
DEALLOCATE PREPARE counter_reaction_replay_index_statement;

-- Existing rows predate local replay. The checkpoint and backfill are atomic so
-- a crash after non-transactional DDL can safely re-enter this migration.
START TRANSACTION;

INSERT IGNORE INTO schema_migrations (version)
VALUES ('V26__counter_reaction_receipt_backfill');

SET @counter_reaction_receipt_backfill_required = ROW_COUNT();

UPDATE counter_event_inbox
SET side_effects_published_at = applied_at
WHERE @counter_reaction_receipt_backfill_required = 1
  AND metric IN ('like', 'fav')
  AND side_effects_published_at IS NULL;

COMMIT;
