-- Deterministic MySQL seed for benchmark users, public posts, reactions, and following state.
-- Override the four session variables before sourcing this file for a smaller profile.
SET @benchmark_users = GREATEST(COALESCE(@benchmark_users, 2000), 2);
SET @benchmark_posts = GREATEST(COALESCE(@benchmark_posts, 10000), 1);
SET @benchmark_interactions = GREATEST(COALESCE(@benchmark_interactions, 100000), 0);
SET @benchmark_relations = GREATEST(COALESCE(@benchmark_relations, 100000), 0);
SET @benchmark_user_base = 910000000000000000;
SET @benchmark_post_base = 920000000000000000;
SET @benchmark_relation_base = 930000000000000000;

SET @previous_cte_max_recursion_depth = @@SESSION.cte_max_recursion_depth;
SET SESSION cte_max_recursion_depth = 100001;

DROP TEMPORARY TABLE IF EXISTS benchmark_numbers;
CREATE TEMPORARY TABLE benchmark_numbers (
    n INT UNSIGNED NOT NULL,
    PRIMARY KEY (n)
) ENGINE=InnoDB;

INSERT INTO benchmark_numbers (n)
WITH RECURSIVE sequence(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1
    FROM sequence
    WHERE n < GREATEST(
        @benchmark_users,
        @benchmark_posts,
        @benchmark_interactions,
        @benchmark_relations
    )
)
SELECT n FROM sequence;

START TRANSACTION;

INSERT INTO users (id, email, nickname, handle, role, created_at, updated_at)
SELECT @benchmark_user_base + n,
       CONCAT('benchmark-', n, '@example.invalid'),
       CONCAT('Benchmark User ', n),
       CONCAT('benchmark-user-', n),
       'USER',
       TIMESTAMPADD(SECOND, n, '2026-01-01 00:00:00'),
       TIMESTAMPADD(SECOND, n, '2026-01-01 00:00:00')
FROM benchmark_numbers
WHERE n <= @benchmark_users
ON DUPLICATE KEY UPDATE
    nickname = VALUES(nickname),
    updated_at = VALUES(updated_at);

INSERT INTO posts (
    id,
    tags,
    title,
    slug,
    description,
    content_url,
    content_object_key,
    content_etag,
    content_size,
    content_sha256,
    creator_id,
    is_top,
    type,
    visible,
    status,
    create_time,
    update_time,
    publish_time
)
SELECT @benchmark_post_base + n,
       JSON_ARRAY('benchmark', IF(MOD(n, 5) = 0, 'hot', 'regular')),
       CONCAT('Benchmark Post ', n),
       CONCAT('benchmark-post-', n),
       CONCAT('Benchmark post ', n),
       CONCAT('/uploads/benchmark/post-', n, '.md'),
       CONCAT('benchmark/post-', n, '.md'),
       CONCAT('benchmark-etag-', n),
       128,
       LOWER(SHA2(CONCAT('benchmark-content-', n), 256)),
       @benchmark_user_base + MOD(n - 1, @benchmark_users) + 1,
       IF(n <= 5, 1, 0),
       'image_text',
       'public',
       'published',
       TIMESTAMPADD(SECOND, n, '2026-01-01 00:00:00'),
       TIMESTAMPADD(SECOND, n, '2026-01-01 00:00:00'),
       TIMESTAMPADD(SECOND, n, '2026-01-01 00:00:00')
FROM benchmark_numbers
WHERE n <= @benchmark_posts
ON DUPLICATE KEY UPDATE
    title = VALUES(title),
    visible = VALUES(visible),
    status = VALUES(status),
    update_time = VALUES(update_time);

INSERT INTO following (id, from_user_id, to_user_id, rel_status, created_at, updated_at)
SELECT @benchmark_relation_base + n,
       @benchmark_user_base + MOD(n - 1, @benchmark_users) + 1,
       @benchmark_user_base + MOD(
           MOD(n - 1, @benchmark_users) + 1 + MOD(FLOOR((n - 1) / @benchmark_users), @benchmark_users - 1),
           @benchmark_users
       ) + 1,
       1,
       TIMESTAMPADD(SECOND, n, '2026-01-02 00:00:00'),
       TIMESTAMPADD(SECOND, n, '2026-01-02 00:00:00')
FROM benchmark_numbers
WHERE n <= LEAST(@benchmark_relations, @benchmark_users * (@benchmark_users - 1))
ON DUPLICATE KEY UPDATE
    rel_status = VALUES(rel_status),
    updated_at = VALUES(updated_at);

DELETE FROM counter_reaction
WHERE entity_type = 'post'
  AND CAST(entity_id AS UNSIGNED)
      BETWEEN @benchmark_post_base + 1 AND @benchmark_post_base + 1000000
  AND user_id
      BETWEEN @benchmark_user_base + 1 AND @benchmark_user_base + 1000000;

INSERT INTO counter_reaction (entity_type, entity_id, metric, user_id, created_at)
SELECT 'post',
       CAST(
           @benchmark_post_base
               + MOD(FLOOR(FLOOR((n - 1) / 2) / @benchmark_users), @benchmark_posts)
               + 1
           AS CHAR
       ),
       IF(MOD(n - 1, 2) = 0, 'like', 'fav'),
       @benchmark_user_base + MOD(FLOOR((n - 1) / 2), @benchmark_users) + 1,
       TIMESTAMPADD(SECOND, n, '2026-01-03 00:00:00')
FROM benchmark_numbers
WHERE n <= @benchmark_interactions
ON DUPLICATE KEY UPDATE
    created_at = VALUES(created_at);

INSERT INTO counter_snapshot (
    entity_type,
    entity_id,
    metric,
    count_value,
    fact_epoch,
    updated_at
)
SELECT 'post',
       CAST(@benchmark_post_base + posts.n AS CHAR),
       metrics.metric,
       COUNT(reactions.user_id),
       0,
       '2026-01-03 00:00:00'
FROM benchmark_numbers posts
CROSS JOIN (
    SELECT 'like' AS metric
    UNION ALL
    SELECT 'fav'
) metrics
LEFT JOIN counter_reaction reactions
  ON reactions.entity_type = 'post'
 AND reactions.entity_id = CAST(@benchmark_post_base + posts.n AS CHAR)
 AND reactions.metric = metrics.metric
WHERE posts.n <= @benchmark_posts
GROUP BY posts.n, metrics.metric
ON DUPLICATE KEY UPDATE
    count_value = VALUES(count_value),
    fact_epoch = VALUES(fact_epoch),
    updated_at = VALUES(updated_at);

COMMIT;

DROP TEMPORARY TABLE benchmark_numbers;
SET SESSION cte_max_recursion_depth = @previous_cte_max_recursion_depth;
