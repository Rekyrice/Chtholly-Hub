# 脚本

| 目录 | 用途 |
|------|------|
| [dev/](dev/) | 本地开发：加载 `.env`、启动/停止前后端 |
| [backup/](backup/) | MySQL 一致性备份、校验与恢复前材料 |
| [oss/](oss/) | OSS Markdown：种子正文与上传 |
| [git-hooks/](git-hooks/) | Git 钩子（如 prepare-commit-msg） |

日常开发从 Monorepo 根目录：

```powershell
.\scripts\dev\start-backend.ps1
.\scripts\dev\start-frontend.ps1
```

首次搭环境见根目录 [README.md](../README.md) 与 [apps/server/db/README.md](../apps/server/db/README.md)。
