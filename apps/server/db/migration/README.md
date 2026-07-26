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

本地 [`apply-migrations.ps1`](../../../../scripts/dev/apply-migrations.ps1) 会按数字顺序执行未登记版本并写入 `schema_migrations`；生产初始化脚本显式执行 SQL。仓库当前没有应用启动时的完整 Flyway 自动迁移。

规则：已在任何共享环境应用的脚本不可修改或重命名；后续修复新增更高版本，并在空库最终结构变化时同步更新 `schema.sql`。演示行数据继续由 `../seed/phase_a_seed.sql` 管理；完整流程见[数据库章节](../../../../docs/development/database.md)。

`V25` 只建表，不导入旧 Redis Bitmap。需要保留既有互动的环境必须在切换前停止写入并执行单独评审的一次性导入；运行期不保留双写或 Bitmap→MySQL 回填。
