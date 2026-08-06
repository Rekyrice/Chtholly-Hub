# 数据库增量脚本

历史 V0–V19 已合并进 [`schema.sql`](../schema.sql)，当前目录保留真实的已有库增量：

| 版本 | 作用 |
|------|------|
| [`V20__knowledge_graph.sql`](V20__knowledge_graph.sql) | 创建知识实体和关系表 |
| [`V21__chtholly_bot_user.sql`](V21__chtholly_bot_user.sql) | 清理冲突 handle，并确保专用珂朵莉账号存在 |
| [`V22__seed_content_identity.sql`](V22__seed_content_identity.sql) | 创建种子内容到实体 ID 的稳定映射表 |
| [`V23__counter_event_inbox_and_snapshot.sql`](V23__counter_event_inbox_and_snapshot.sql) | 创建计数事件幂等收件箱与持久化快照表 |
| [`V24__draft_edit_preview.sql`](V24__draft_edit_preview.sql) | 创建受控草稿编辑预览表 |
| [`V25__counter_reaction.sql`](V25__counter_reaction.sql) | 创建点赞/收藏成员关系事实表 |
| [`V26__counter_reaction_side_effect_receipt.sql`](V26__counter_reaction_side_effect_receipt.sql) | 增加互动事件级副作用回执和 Outbox 回放索引 |
| [`V27__dead_letter_replay_state.sql`](V27__dead_letter_replay_state.sql) | 区分自动重试、人工重放进行中与结果待核对状态，并记录 token/开始/截止时间 |
| [`V28__post_projection_receipt.sql`](V28__post_projection_receipt.sql) | 创建文章派生投影的每帖顺序游标与持久完成回执 |
| [`V29__user_refresh_session_epoch.sql`](V29__user_refresh_session_epoch.sql) | 把用户级 refresh session 失效代际迁入 MySQL 权威列 |
| [`V30__user_comment_activity_index.sql`](V30__user_comment_activity_index.sql) | 增加公开用户评论活动查询索引 |

本地 [`apply-migrations.ps1`](../../../../scripts/dev/apply-migrations.ps1) 会按数字顺序执行未登记版本并写入 `schema_migrations`；生产初始化脚本显式执行 SQL。仓库当前没有应用启动时的完整 Flyway 自动迁移。

规则：已在任何共享环境应用的脚本不可修改或重命名；后续修复新增更高版本，并在空库最终结构变化时同步更新 `schema.sql`。演示行数据继续由 `../seed/phase_a_seed.sql` 管理；完整流程见[数据库章节](../../../../docs/development/database.md)。

`V25` 只建表，不导入旧 Redis Bitmap。需要保留既有互动的环境必须在切换前停止写入并执行单独评审的一次性导入；运行期不保留双写或 Bitmap→MySQL 回填。

`V26` 的列和索引 DDL 可重入；事务内 checkpoint 保证已有 reaction Inbox 的历史回执只回填一次。新应用依赖该列执行本地 Outbox 回放和安全清理，因此已有库应先应用迁移再启动新版本。

`V27` 的枚举和三列 DDL 可重入并保留旧状态行；新应用写入 `REPLAYING`/`UNCERTAIN` 及 `replay_attempt_token`、`replay_started_at`、`replay_deadline_at`，因此必须先完成该迁移再启动。

`V28` 可重复创建文章投影回执表；回执以 Outbox 事件 ID 为主键并随 Outbox 级联删除，帖子游标则跨保留期清理继续保存事件高水位。新应用依赖两者区分输入：已有回执的重复事件直接跳过；旧事件父 Outbox 仍在但回执缺失时重跑幂等投影并补回执而不回退游标；父行已清理的迟到旧消息根据游标高水位直接跳过；较新事件只有在投影与回执成功后才推进游标，缺少父行时回执约束使事务失败并向调用方传播，不能静默确认。因此必须先迁移再启动。

`V29` 可重入地增加或规范化 `users.refresh_session_epoch BIGINT NOT NULL DEFAULT 1`。新应用不再接受旧版 Redis 裸 membership，也不在运行期迁移旧 key；已有 refresh session 会一次性失效。旧节点不读取 MySQL epoch，因而该版本不支持滚动混部：发布必须先排空认证流量并停止全部旧节点，再执行 V29，验证列定义与存量用户均为正 epoch 后一次性启动新节点。回滚到旧节点前必须先完成 refresh membership 的安全失效，不能直接恢复旧进程。注册首枚 `mysql:1` membership 只允许 pending-token 窄端口在用户尚未提交时建立，回滚或完成状态未知时补偿；审计与注册事件在提交后执行。

`V30` 可重放地为 `comments` 维护 `(user_id, deleted_at, created_at, id)` 索引：索引缺失时创建，同名索引列定义错误时删除并重建，定义已经正确时不改动。
