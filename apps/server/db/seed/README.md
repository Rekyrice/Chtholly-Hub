# Phase A 种子数据

全量 schema、V20/V21 增量与生产边界见[数据库章节](../../../../docs/development/database.md)。

`phase_a_seed.sql` 写入：

- 用户 **Rekyrice**（`id=1`，`handle=Rekyrice`）
- 3 篇已发布帖子（slug、OSS `content_url` 等）

使用 **`ON DUPLICATE KEY UPDATE`**，可重复导入以修正元数据，不会重复插行。

## 导入

在 Monorepo 根目录执行（密码含 `!` 时用 `MYSQL_PWD` 环境变量）：

```powershell
docker cp apps/server/db/seed/phase_a_seed.sql mysql:/tmp/phase_a_seed.sql
docker exec -i -e MYSQL_PWD='你的密码' mysql mysql -uroot --default-character-set=utf8mb4 chtholly -e "source /tmp/phase_a_seed.sql"
```

**务必** `--default-character-set=utf8mb4`，否则中文乱码。

## 导入后

帖子 `content_url` 指向 OSS。若正文未上传或仍是旧文案，在仓库根目录：

```powershell
cd scripts/oss
npm install   # 首次
node upload-seed-markdown.mjs
```

正文源文件：`scripts/oss/seed/*.md`。
