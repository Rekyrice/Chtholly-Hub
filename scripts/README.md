# 脚本入口

| 目录/文件 | 真实入口 | 用途 |
|-----------|----------|------|
| [`dev/`](dev/) | `start-backend.ps1`、`start-frontend.ps1`、`stop-backend.ps1` | 加载根 `.env`，启动/停止本地应用 |
| [`dev/`](dev/) | `apply-migrations.ps1`、`run-seed.ps1`、`ensure-kafka-topics.ps1` | 本地数据库增量、应用 seed、Kafka 主题 |
| [`backup/`](backup/) | `backup-mysql.ps1`、`backup-redis.ps1` | MySQL/Redis 一致性备份、校验与恢复前材料 |
| [`deploy/`](deploy/) | `ecs-bootstrap.sh`、`ecs-init-db.sh` | 单机生产 Compose 首次部署与数据库初始化 |
| [`git-hooks/`](git-hooks/) | `prepare-commit-msg` | 可选 Git 提交信息钩子；仓库不会自动安装 |
| [`oss/`](oss/README.md) | `upload-seed-markdown.mjs`、`upload-markdown.mjs` | 上传 Phase A 或单篇 Markdown 正文 |
| [`seed-content/`](seed-content/) | `render-community-interaction-review.mjs` | 生成 content-v3 社区资料与互动的本地只读审阅页 |
| [`benchmark/`](benchmark/) | `environment.ps1`、`run.ps1`、`seed.ps1`、`summarize.ps1`、`trace-replay.ps1` | 运行隔离缓存基准，或脱敏导出并比较固定 Agent Trace 回放 |

日常 Windows 开发从 Monorepo 根目录运行：

```powershell
.\scripts\dev\start-backend.ps1
.\scripts\dev\start-frontend.ps1
```

生成互动内容包的本地只读审阅页：

```powershell
node .\scripts\seed-content\render-community-interaction-review.mjs
```

默认输出到被 Git 忽略的 `.codex-tmp/community-interactions/review/index.html`。生成器拒绝仓库外路径和未被 Git 忽略的输出路径，并展示总量、账号参与、逐文章热度、零评论目标与评论月份分布。

启动脚本与裸 Maven/Next 的环境边界见[开发入口](../docs/development/README.md)，测试命令见[测试与验证](../docs/development/testing.md)，数据库脚本关系见[数据库](../docs/development/database.md)，生产脚本与回滚边界见[生产部署](../docs/operations/deployment.md)。
