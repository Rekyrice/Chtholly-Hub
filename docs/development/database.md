# 数据库

## 三类入口

| 入口 | 当前职责 | 适用场景 |
|------|----------|----------|
| [`schema.sql`](../../apps/server/db/schema.sql) | 合并 V0–V19 历史并保留当前最终表形的全量开发入口；已包含知识图谱表与专用珂朵莉账号的最终状态 | 新建或重建空数据库 |
| [`migration/`](../../apps/server/db/migration/README.md) | 现存增量：`V20__knowledge_graph.sql`、`V21__chtholly_bot_user.sql` | 已有数据库向前演进，以及脚本登记 |
| [`phase_a_seed.sql`](../../apps/server/db/seed/phase_a_seed.sql) | 幂等写入 Rekyrice 用户与 3 篇已发布帖子元数据 | 需要 Phase A 演示数据的环境 |

`schema.sql` 与 `migration/` 不是一套完整的 Flyway 历史。仓库当前没有声明由应用启动自动执行 Flyway；本地用 [`apply-migrations.ps1`](../../scripts/dev/apply-migrations.ps1) 读取 `schema_migrations` 并执行未登记脚本，生产初始化脚本则显式导入 SQL。不要把“目录名符合 Flyway 命名”写成“已启用 Flyway 自动迁移”。

## schema 与 V20/V21 的关系

- `V20__knowledge_graph.sql` 创建 `knowledge_entities` 与 `knowledge_relations`。
- `V21__chtholly_bot_user.sql` 清理冲突的 `chtholly` handle，并确保 ID `888888888888888888` 的专用账号存在。
- 当前 `schema.sql` 已折叠两者的最终结构/数据形态，便于空库一次初始化；已有库仍依赖 V20/V21 增量前进。
- 本地增量脚本会在检测到目标表或账号已存在时补登记 V20/V21，避免对由全量 schema 建出的库重复执行。

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

- 新环境由 [`ecs-init-db.sh`](../../scripts/deploy/ecs-init-db.sh) 按当前实现导入 `schema.sql`、顺序执行现存 `V*.sql`，再导入 Phase A seed。脚本需要 root DDL 权限；应用账号只保留 DML 权限。
- 已有生产库不能靠重新导入 `schema.sql` 推断 ALTER；先备份并验证增量脚本，再在维护窗口执行。已应用的版本脚本不可修改，修复必须新增更高版本。
- DDL 或数据修复通常不可随应用镜像自动回滚。回滚前要区分可逆应用版本与不可逆数据库状态，必要时使用已验证的前向修复或备份恢复。

## 验证

初始化或迁移后至少检查：目标表/索引存在、`schema_migrations` 的登记符合本地脚本预期、Phase A 三篇帖子与 OSS 对象一一对应、应用账号可 DML 但不能 DDL，并启动后端验证健康检查、Feed 与需要的搜索/详情链路。
