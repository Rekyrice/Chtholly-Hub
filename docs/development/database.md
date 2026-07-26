# 数据库

## 三类入口

| 入口 | 当前职责 | 适用场景 |
|------|----------|----------|
| [`schema.sql`](../../apps/server/db/schema.sql) | 合并 V0–V19 历史并保留当前最终表形的全量开发入口；已包含 V20–V27 的最终结构与种子账号状态 | 新建或重建空数据库 |
| [`migration/`](../../apps/server/db/migration/README.md) | 现存增量：`V20__knowledge_graph.sql` 至 `V27__dead_letter_replay_state.sql` | 已有数据库向前演进，以及脚本登记 |
| [`phase_a_seed.sql`](../../apps/server/db/seed/phase_a_seed.sql) | 幂等写入 Rekyrice 用户与 3 篇已发布帖子元数据 | 需要 Phase A 演示数据的环境 |

`schema.sql` 与 `migration/` 不是一套完整的 Flyway 历史。仓库当前没有声明由应用启动自动执行 Flyway；本地用 [`apply-migrations.ps1`](../../scripts/dev/apply-migrations.ps1) 读取 `schema_migrations` 并执行未登记脚本，生产初始化脚本则显式导入 SQL。不要把“目录名符合 Flyway 命名”写成“已启用 Flyway 自动迁移”。

## schema 与 V20–V27 的关系

- `V20__knowledge_graph.sql` 创建 `knowledge_entities` 与 `knowledge_relations`。
- `V21__chtholly_bot_user.sql` 清理冲突的 `chtholly` handle，并确保 ID `888888888888888888` 的专用账号存在。
- `V22__seed_content_identity.sql` 创建种子内容到实体 ID 的稳定映射表。
- `V23__counter_event_inbox_and_snapshot.sql` 创建按 `event_id` 幂等的计数收件箱，以及按实体和指标聚合的持久化快照。
- `V24__draft_edit_preview.sql` 创建同步、显式批准的草稿编辑预览表。
- `V25__counter_reaction.sql` 创建点赞/收藏成员关系事实表；`(entity_type, entity_id, metric, user_id)` 主键提供目标状态幂等，`metric` 仅允许 `like` 或 `fav`。
- `V26__counter_reaction_side_effect_receipt.sql` 为 `counter_event_inbox` 增加事件级 `side_effects_published_at`，为 Outbox 增加 `(aggregate_type, type, id)` 回放索引，并用内部 checkpoint 只执行一次历史 reaction Inbox 回执回填。
- `V27__dead_letter_replay_state.sql` 扩展 `dead_letter_messages.status`，以 `REPLAYING` 和 `UNCERTAIN` 区分人工重放进行中、结果待核对与普通自动 `RETRYING`；同时增加 attempt token、开始时间和恢复截止时间。
- 当前 `schema.sql` 已折叠这些版本的最终结构/数据形态，便于空库一次初始化；已有库仍依赖对应增量脚本前进。
- 本地增量脚本会为部分可可靠识别的历史结构补登记版本；其余 `CREATE ... IF NOT EXISTS` 脚本会安全执行并登记，避免重复建表。

## V25 迁移边界

`V25` 是干净迁移：它只创建 `counter_reaction`，不会把旧 Redis Bitmap 自动导入 MySQL。新环境或无需保留旧互动的环境可直接应用；内容包与基准 seed 会先写 MySQL 关系，再通过校准生成投影。

若已有环境必须保留仅存在于旧 Bitmap 的成员关系，不能把空表上线后继续混用双权威。切换前应停止互动写入，在同一维护窗口备份 MySQL 与 Redis，并使用单独评审的、有界且幂等的一次性导入过程写入 `counter_reaction`；核对完成后再启用新版本并从 MySQL 重建 Redis。运行期不保留 Bitmap→MySQL 反向同步，恢复 RDB 也不得覆盖较新的 MySQL 事实。

## V26 回放与清理边界

`V26` 的 DDL 可重复进入：缺失时增加回执列，已存在但定义不符的同名回放索引会先被替换。升级已有库时必须先停止旧版本的互动写入、本地回放和相关 Kafka 消费者，确认不再插入旧格式 Inbox，再执行 V26 并验证后启动新版本；不支持旧、新应用跨该 checkpoint 混跑。DDL 完成后，事务内插入内部 checkpoint `V26__counter_reaction_receipt_backfill`；只有首次插入 checkpoint 的执行才把当时已有 like/fav Inbox 的 `side_effects_published_at` 回填为原 `applied_at`，避免把已经处理过的历史事件全部当作新副作用重放。该回填以防重复为优先，无法证明每个历史监听器实际完成：若旧进程曾在 Inbox 提交后、发布副作用前退出，对应事件也会被视作完成，迁移不会自动补偿。新 Inbox 行的回执保持 `NULL`，直到同步 Spring 事件发布成功。历史上仍没有 Inbox 的 Outbox 代表未完成事件，会由本地回放正常处理。

互动 Outbox 清理比普通 Outbox 多一个条件：对应 Inbox 必须存在且 `side_effects_published_at` 非空。这样保留期限到达也不会删除新链路中仍明确需要恢复投影或副作用的唯一持久输入；但 V26 保守回填所覆盖的历史行已经被有意视作完成，清理前应先结束必要的历史审计。事件级回执不等于逐监听器 exactly-once，迁移也不会推断或补造某个监听器的独立完成状态。

## V27 死信重放状态边界

`V27` 可重复执行，并保留已有 `PENDING`、`RETRYING`、`DEAD` 行；它不创建新表或复制消息，但会扩展状态枚举，并可重入地增加或规范化 `replay_attempt_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin`、`replay_started_at DATETIME(3)`、`replay_deadline_at DATETIME(3)`。新应用用唯一 attempt token 抢占 `REPLAYING`，以数据库时钟记录一次人工发送的开始时间和恢复截止时间。截止时间由 producer 的实际 `max.block.ms + delivery.timeout.ms + 30 秒` 得出；HTTP 的 10 秒等待窗口不是 producer 截止时间，超时或中断时行继续保持 `REPLAYING`。

非空 broker `SendResult`、异步失败或空结果都由同 token 的终态 CAS 收敛；状态写入失败会留下可诊断的 `REPLAYING`。管理员列表和操作响应返回当前 `replayAttemptToken` 作为并发代际标识，而非授权凭证。只有数据库截止时间已过且 token 匹配的 claim 才能先通过 `recover-expired` 进入 `UNCERTAIN`，再由管理员核对发布证据并携带同 token 显式 resolve；旧 broker 回调和旧人工请求都不能完成后续 claim。迁移前的 `RETRYING` 仍只表示消费者自动重试记录，不能通过人工 resolve 改写。已有库必须先完成 V27，再启动引用新状态和列的应用；表较大时应预估多次 `ALTER TABLE` 的元数据锁窗口。

## 开发初始化

空库先按 [`apps/server/db/README.md`](../../apps/server/db/README.md) 导入 `schema.sql`。之后从仓库根目录运行推荐后端脚本时，会在启动前调用 `apply-migrations.ps1`：

```powershell
.\scripts\dev\start-backend.ps1
```

只需要补增量时可显式运行：

```powershell
.\scripts\dev\apply-migrations.ps1
```

脚本优先使用名为 `mysql` 的本地容器，否则使用本机 `mysql` CLI，并从根 `.env` 读取连接参数。它不是通用生产迁移器，执行前仍需确认目标库和备份。

## Phase A 行数据与 OSS 正文

`phase_a_seed.sql` 只写用户、帖子元数据、`content_url` 与 `content_object_key`，使用 `ON DUPLICATE KEY UPDATE` 允许重复导入。三篇 Markdown 正文位于 [`scripts/oss/seed`](../../scripts/oss/seed/)，通过 [`upload-seed-markdown.mjs`](../../scripts/oss/upload-seed-markdown.mjs) 上传；数据库键与对象路径必须一致。

```powershell
docker cp apps/server/db/seed/phase_a_seed.sql mysql:/tmp/phase_a_seed.sql
docker exec -i -e MYSQL_PWD='你的密码' mysql mysql -uroot --default-character-set=utf8mb4 chtholly -e "source /tmp/phase_a_seed.sql"

cd scripts/oss
npm install
node upload-seed-markdown.mjs
```

密钥只放本地 `.env`，不要写入命令历史、文档或提交。

## 生产变更边界

- 新环境由 [`ecs-init-db.sh`](../../scripts/deploy/ecs-init-db.sh) 按当前实现导入 `schema.sql`、顺序执行现存 `V*.sql`，再导入 Phase A seed。脚本需要 root DDL 权限；应用账号只保留 DML 权限。已有 Redis-only 互动数据必须先按上节完成一次性迁移，不能用初始化脚本代替。已有库必须先静默旧版本互动写入与消费者，再依次应用并验证 `V26`、`V27`，最后启动新应用；否则既可能因缺列/枚举值失败，也可能让 checkpoint 之后的旧版本事件被新版本误判为待补发。
- 已有生产库不能靠重新导入 `schema.sql` 推断 ALTER；先备份并验证增量脚本，再在维护窗口执行。已应用的版本脚本不可修改，修复必须新增更高版本。
- DDL 或数据修复通常不可随应用镜像自动回滚。回滚前要区分可逆应用版本与不可逆数据库状态，必要时使用已验证的前向修复或备份恢复。

## 验证

初始化或迁移后至少检查：目标表/索引存在、`schema_migrations` 的版本登记和 `V26__counter_reaction_receipt_backfill` 内部 checkpoint 符合预期、`counter_reaction` 主键和 metric 约束有效、`counter_event_inbox.side_effects_published_at` 与 `ix_outbox_reaction_replay` 定义正确、死信状态枚举包含 `REPLAYING`/`UNCERTAIN`，且 `replay_attempt_token`、`replay_started_at`、`replay_deadline_at` 的类型与字符集符合 V27、Phase A 三篇帖子与 OSS 对象一一对应、应用账号可 DML 但不能 DDL，并启动后端验证健康检查、互动写入/读取、Outbox 回放、Feed 与需要的搜索/详情链路。
