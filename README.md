# Chtholly Hub

[![CI](https://github.com/Rekyrice/Chtholly-Hub/actions/workflows/ci.yml/badge.svg)](https://github.com/Rekyrice/Chtholly-Hub/actions/workflows/ci.yml)

Chtholly Hub 是一个从动漫博客演进而来的社区平台，并提供可选的 AI 内容与角色 Agent 能力。

## 核心能力

- **阅读与创作**：阅读主题文章，使用 Markdown 完成创作、预览与发布。
- **社区互动**：通过评论、点赞、收藏、关注和通知连接作者与读者。
- **内容发现**：按归档、标签、全文搜索和个性化推荐发现内容。
- **角色 Agent**：与可选的角色 Agent 实时对话，并调用站内知识与番剧工具。
- **管理与部署**：提供内容管理、运行健康检查和容器化部署入口。

## 仓库结构

```text
apps/web/     Next.js Web 应用
apps/server/  Spring Boot API 与后台任务
docs/         架构、开发和运维知识库
docker/       容器与反向代理配置
scripts/      开发、部署和数据脚本
AGENTS.md     Agent 全局目录与工作契约
```

## 快速开始

复制开发环境变量模板：

```powershell
Copy-Item .env.example .env
```

启动后端（默认 `http://localhost:8888`）：

```powershell
cd apps/server
mvn spring-boot:run
```

启动前端（默认 `http://localhost:3000`）：

```powershell
cd apps/web
npm install
npm run dev
```

外部基础设施见[部署文档](docs/operations/deployment.md)，数据库初始化见[数据库开发文档](docs/development/database.md)。

## 文档导航

- [Agent 全局目录与工作契约](AGENTS.md)
- [项目知识库](docs/README.md)
- [后端应用入口](apps/server/README.md)
- [前端应用入口](apps/web/README.md)
- [部署文档](docs/operations/deployment.md)

## 验证

```powershell
cd apps/server
mvn test

cd ../web
npm run test:run
```
