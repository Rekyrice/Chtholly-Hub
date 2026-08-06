# 数据库操作入口（apps/server/db）

Chtholly Hub 使用 MySQL。稳定的 schema、增量、seed 关系与生产边界见[数据库章节](../../../docs/development/database.md)；本页保留可直接执行的局部命令。

## 目录结构

```text
db/
├── schema.sql          # 开发阶段全量建表脚本
├── migration/          # 已有数据库的增量脚本（当前 V20–V30）
└── seed/               # 开发/演示用种子数据
    └── phase_a_seed.sql
```

## 开发环境初始化

1. 创建数据库：

   ```powershell
   docker exec -i -e MYSQL_PWD='你的密码' mysql mysql -uroot --default-character-set=utf8mb4 -e "CREATE DATABASE IF NOT EXISTS chtholly CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
   ```

2. 导入全量结构：

   ```powershell
   docker cp apps/server/db/schema.sql mysql:/tmp/schema.sql
   docker exec -i -e MYSQL_PWD='你的密码' mysql mysql -uroot --default-character-set=utf8mb4 chtholly -e "source /tmp/schema.sql"
   ```

3. 如需演示数据，再执行 `seed/` 下的脚本。正文 Markdown 文件由 OSS 种子流程管理，不放在数据库目录中。

## 增量脚本

- [`V20__knowledge_graph.sql`](migration/V20__knowledge_graph.sql)：知识实体与关系表。
- [`V21__chtholly_bot_user.sql`](migration/V21__chtholly_bot_user.sql)：确保专用珂朵莉账号使用高 ID。
- [`V22__seed_content_identity.sql`](migration/V22__seed_content_identity.sql)：种子内容到实体 ID 的稳定映射。
- [`V23__counter_event_inbox_and_snapshot.sql`](migration/V23__counter_event_inbox_and_snapshot.sql)：计数事件幂等收件箱与持久化快照。
- [`V24__draft_edit_preview.sql`](migration/V24__draft_edit_preview.sql)：受控草稿编辑预览。
- [`V25__counter_reaction.sql`](migration/V25__counter_reaction.sql)：点赞/收藏成员关系事实。
- [`V26__counter_reaction_side_effect_receipt.sql`](migration/V26__counter_reaction_side_effect_receipt.sql)：互动事件级副作用回执与 Outbox 回放索引。
- [`V27__dead_letter_replay_state.sql`](migration/V27__dead_letter_replay_state.sql)：人工死信重放状态、attempt token 与数据库时钟截止时间。
- [`V28__post_projection_receipt.sql`](migration/V28__post_projection_receipt.sql)：文章派生投影的每帖顺序游标与 MySQL 完成回执。
- [`V29__user_refresh_session_epoch.sql`](migration/V29__user_refresh_session_epoch.sql)：用户级 refresh session 失效代际的 MySQL 权威列。
- [`V30__user_comment_activity_index.sql`](migration/V30__user_comment_activity_index.sql)：公开用户评论活动查询的 `(user_id, deleted_at, created_at, id)` 索引。

`schema.sql` 已包含当前最终表形，空库无需为 V0–V19 逐个找历史脚本。本地已有库可从根目录运行 `.\scripts\dev\apply-migrations.ps1`；它使用 `schema_migrations` 登记，不代表应用启用了 Flyway。新增变更只追加更高版本的 `V*.sql`，已应用脚本不得修改。

`V25` 不从旧 Redis Bitmap 回填关系。新环境按空表启动；必须保留既有 Redis-only 互动时，先停止写入并完成经单独评审的一次性有界导入，再由 MySQL 重建 Redis 投影。详细边界见[数据库章节](../../../docs/development/database.md#v25-迁移边界)。

`V26` 通过内部 checkpoint 只在首次执行时把已有 reaction Inbox 的副作用回执回填为 `applied_at`；新事件保持待发布，历史上仍无 Inbox 的 Outbox 保持可恢复。清理任务只有在对应回执非空后才允许删除 reaction Outbox。详细边界见[数据库章节](../../../docs/development/database.md#v26-回放与清理边界)。

`V27` 保留旧死信状态，扩展 `REPLAYING`/`UNCERTAIN`，并可重入地增加 `replay_attempt_token`、`replay_started_at`、`replay_deadline_at`；已有库必须先迁移，再启动会写入新状态与列的应用。详细边界见[数据库章节](../../../docs/development/database.md#v27-死信重放状态边界)。

`V28` 创建 `post_projection_cursor` 与 Outbox 级联的 `post_projection_receipt`。同一帖子以 `SELECT ... FOR UPDATE` 跨实例串行：已有回执的重复事件直接跳过；事件 ID 不大于游标但父 Outbox 仍存在且回执缺失时，仍按 MySQL 当前事实重跑全部幂等投影并补回执，但不回退游标；父 Outbox 与级联回执已被保留期清理时，由仍保留的游标高水位把旧消息识别为迟到重复并跳过。只有较新的事件在全部投影与回执成功后才推进游标；较新消息缺少父 Outbox 时，回执父行约束使 MySQL 事务失败、游标不变并向调用方传播，不能使用清理快路径或静默确认。清理任务不会提前删除缺少回执的文章 Outbox。详细边界见[数据库章节](../../../docs/development/database.md#v28-文章投影回放边界)。

`V29` 为 `users` 增加 `refresh_session_epoch BIGINT NOT NULL DEFAULT 1`。新版本只接受 Redis 中携带 `mysql:<epoch>` 的 refresh membership；旧裸值不迁移，因此已有 refresh session 会在切换时一次性失效。该版本禁止新旧应用混部：必须在维护窗口排空认证流量并停止全部旧节点，应用 V29，确认列定义与存量正 epoch 后，再一次性启动新节点；回滚旧实现前必须先安全失效全部 refresh membership。注册首枚 membership 由外层 MySQL 事务中的 pending-token 窄端口建立，回滚或完成状态未知时 best-effort 补偿，注册审计与事件只在提交后执行。详细边界见[数据库章节](../../../docs/development/database.md#v29-refresh-session-权威切换边界)。

`V30` 为 `comments` 增加用户评论活动查询索引。脚本可重复执行：缺少索引时创建；同名索引已存在但列顺序或定义不符时删除并重建；定义正确时保持不变。详细边界见[数据库章节](../../../docs/development/database.md#v30-用户评论活动索引边界)。

## 数据库与 OSS 的分工

| 位置 | 内容 |
|------|------|
| `db/schema.sql` | 表结构、索引和少量基础数据 |
| `db/seed/` | 开发/演示用的行数据 |
| `scripts/oss/seed/` | Markdown 正文文件，上传到 OSS `post/` 目录 |

数据库中的 `slug` / `objectKey` 需要和 OSS 中的正文对象保持一致，否则详情页能打开元数据，但正文会加载失败。
