# Chtholly Hub 项目知识库

本目录面向需要理解、开发或运行 Chtholly Hub 的维护者。先按当前任务选择入口，再进入具体章节；仓库级工作约束以根目录 [AGENTS.md](../AGENTS.md) 为准。

## 按任务阅读

| 任务 | 先读 | 继续深入 |
|------|------|----------|
| 理解系统边界与依赖 | [架构首页](architecture/README.md) | [数据与存储](architecture/data-and-storage.md)、[请求链路](architecture/request-flows.md) |
| 修改后端业务 | [后端架构](architecture/backend.md) | [后端局部规则](../apps/server/AGENTS.md)、[测试矩阵](development/testing.md) |
| 修改前端 UI | [前端架构](architecture/frontend.md) | [前端局部规则](../apps/web/AGENTS.md)、[前端应用入口](../apps/web/README.md) |
| 修改角色 Agent | [Agent 系统](architecture/agent-system.md) | [请求链路](architecture/request-flows.md)、[配置](development/configuration.md) |
| 修改数据库或外部存储 | [数据与存储](architecture/data-and-storage.md) | [数据库开发](development/database.md)、[数据库操作入口](../apps/server/db/README.md) |
| 搭建开发环境或验证改动 | [开发入口](development/README.md) | [配置](development/configuration.md)、[测试矩阵](development/testing.md) |
| 部署与排障 | [部署文档](operations/deployment.md) | [Docker 操作入口](../docker/README.md)、[脚本入口](../scripts/README.md) |

## 按主题阅读

- [Architecture](architecture/README.md)：稳定的系统边界、组件关系、领域职责与端到端链路。
- [Development](development/README.md)：本地开发、配置、数据库变更与验证方法。
- [Operations](operations/deployment.md)：外部基础设施、生产部署、验证与回滚边界。

## 文档维护规则

- **稳定知识**放在 `docs/architecture`、`docs/development` 和 `docs/operations`，描述长期有效的职责、边界、决策与导航；事实变化时与代码一起更新。
- **局部操作文档**放在对应应用或工具目录，例如 `apps/server/README.md`、`apps/server/db/README.md`、`docker/README.md` 和 `scripts/README.md`，保留可直接执行的命令与局部注意事项，不复制系统级说明。
- **临时文档**放在被忽略的 `docs/local`、`docs/drafts`、`docs/superpowers` 或项目级临时目录中；草稿、一次性计划和工具产物不得冒充稳定知识，也不得通过强制添加绕过忽略规则。
- 根 `README.md` 服务首次访问者，根 `AGENTS.md` 负责 Agent 路由与全局契约；新增内容应放入最接近其读者和生命周期的位置，通过链接互相导航。
