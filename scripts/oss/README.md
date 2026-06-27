# OSS 脚本

阿里云 OSS 用于存储帖子 Markdown 正文（Phase A 种子 + 后续发帖直传）。

## 目录结构

```
scripts/oss/
├── seed/                      # Phase A 正文源文件（纳入 Git）
│   ├── welcome.md
│   ├── winter-2026.md
│   └── why-chtholly.md
├── upload-seed-markdown.mjs   # 批量上传 seed/ 下三篇 → post/*.md
├── upload-markdown.mjs        # 单文件上传（任意 objectKey）
├── package.json
└── README.md
```

## 前置

根目录 `.env` 已配置：

- `OSS_ENDPOINT`
- `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET`
- `OSS_BUCKET`（如 `chtholly-hub-dev`）

```powershell
cd scripts/oss
npm install
```

## 批量上传种子正文（常用）

与 `apps/server/db/seed/phase_a_seed.sql` 中的 `content_object_key` 对应：

```powershell
node upload-seed-markdown.mjs
```

上传后对象 ACL 为 **public-read**，详情页通过 `content_url` 拉取。

## 单文件上传

```powershell
node upload-markdown.mjs post/my-post.md ../../path/to/local.md
```

## 与数据库的关系

| 步骤 | 脚本 / 目录 |
|------|-------------|
| 帖子元数据（title、slug、URL） | `apps/server/db/seed/phase_a_seed.sql` |
| 正文 `.md` | 本目录 `seed/` → OSS `post/` |

先导入 SQL，再上传 Markdown，最后启动后端验收 Feed / 详情页。
