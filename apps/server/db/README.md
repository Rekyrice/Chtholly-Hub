# 数据库操作入口（apps/server/db）

Chtholly Hub 使用 MySQL。稳定的 schema、增量、seed 关系与生产边界见[数据库章节](../../../docs/development/database.md)；本页保留可直接执行的局部命令。

## 目录结构

```text
db/
├── schema.sql          # 开发阶段全量建表脚本
├── migration/          # 已有数据库的增量脚本（当前 V20–V23）
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

`schema.sql` 已包含当前最终表形，空库无需为 V0–V19 逐个找历史脚本。本地已有库可从根目录运行 `.\scripts\dev\apply-migrations.ps1`；它使用 `schema_migrations` 登记，不代表应用启用了 Flyway。新增变更只追加更高版本的 `V*.sql`，已应用脚本不得修改。

## 数据库与 OSS 的分工

| 位置 | 内容 |
|------|------|
| `db/schema.sql` | 表结构、索引和少量基础数据 |
| `db/seed/` | 开发/演示用的行数据 |
| `scripts/oss/seed/` | Markdown 正文文件，上传到 OSS `post/` 目录 |

数据库中的 `slug` / `objectKey` 需要和 OSS 中的正文对象保持一致，否则详情页能打开元数据，但正文会加载失败。
