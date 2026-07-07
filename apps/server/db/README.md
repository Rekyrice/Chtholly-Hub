# 数据库脚本（apps/server/db）

Chtholly Hub 后端使用 MySQL 数据库。开发阶段以 `schema.sql` 作为唯一的全量结构入口，用来一次性创建当前所需的全部表、索引和基础种子数据。

## 目录结构

```text
db/
├── schema.sql          # 开发阶段全量建表脚本
├── migration/          # 预留给后续 Flyway 迁移脚本
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

3. 如需演示数据，再执行 `seed/` 下的脚本。正文 Markdown 文件仍然由 OSS 种子流程管理，不放在数据库目录中。

## 关于 migration/

`migration/` 目录目前只作为占位保留。开发阶段已经将 V0-V19 的历史迁移内容合并进 `schema.sql`，新同学或本地环境重建数据库时不需要逐个执行迁移脚本。

上线或进入稳定发布流程后，再基于当时确认的 `schema.sql` 拆回独立 Flyway 迁移脚本，并按版本号维护增量变更。

## 数据库与 OSS 的分工

| 位置 | 内容 |
|------|------|
| `db/schema.sql` | 表结构、索引和少量基础数据 |
| `db/seed/` | 开发/演示用的行数据 |
| `scripts/oss/seed/` | Markdown 正文文件，上传到 OSS `post/` 目录 |

数据库中的 `slug` / `objectKey` 需要和 OSS 中的正文对象保持一致，否则详情页能打开元数据，但正文会加载失败。
