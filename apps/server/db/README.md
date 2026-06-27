# 数据库脚本（`apps/server/db`）

Chtholly Hub 后端使用 MySQL 库 **`chtholly`**。本目录按用途分层，**不要**把业务 Markdown 正文放这里（正文在 `scripts/oss/seed/`）。

## 目录结构

```
db/
├── schema.sql          # 全量建表（全新空库时使用）
├── migration/          # 增量结构变更（Flyway 风格命名）
└── seed/               # 开发/演示用数据（可重复执行）
    └── phase_a_seed.sql
```

## 新环境初始化顺序

1. **建库 + 表结构**

   ```powershell
   docker exec -i -e MYSQL_PWD='你的密码' mysql mysql -uroot --default-character-set=utf8mb4 -e "CREATE DATABASE IF NOT EXISTS chtholly CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
   docker cp apps/server/db/schema.sql mysql:/tmp/schema.sql
   docker exec -i -e MYSQL_PWD='你的密码' mysql mysql -uroot --default-character-set=utf8mb4 chtholly -e "source /tmp/schema.sql"
   ```

   若库已存在且只需追增量，按序执行 `migration/` 下脚本。

2. **导入种子数据**（用户 Rekyrice + 3 篇帖子元数据）— 见 [seed/README.md](seed/README.md)

3. **上传 Markdown 正文到 OSS** — 见仓库 [scripts/oss/README.md](../../../scripts/oss/README.md)

## 与 OSS 的分工

| 位置 | 内容 |
|------|------|
| `db/seed/` | 表里的行：用户、帖子 title/slug/tags、`content_url` 等 |
| `scripts/oss/seed/` | `.md` 正文文件，上传到 OSS `post/` 目录 |

两者需 **slug / objectKey 一致**，否则详情页能打开但正文 404。
