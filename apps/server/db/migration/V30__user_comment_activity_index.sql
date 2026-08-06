ALTER TABLE comments ADD KEY ix_comments_user_deleted_ct (user_id, deleted_at, created_at, id);
