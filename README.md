# Chtholly Hub

Rekyrice 的动漫博客向社区演进的全栈平台（秋招 2026 作品集）。中文名「依米花」为虚构花名，寓意见站内 About 页。

- **GitHub**: https://github.com/Rekyrice/Chtholly-Hub
- **架构**: Monorepo — Next.js 16 前端 + Spring Boot 3.2 后端
- **技术栈**: Java 21 · MyBatis · MySQL · Redis · Kafka · Elasticsearch · 阿里云 OSS · Spring AI（可选）

## 已实现能力

| 模块 | 说明 |
|------|------|
| 博客阅读 | 公开 Feed、slug 详情、归档、标签筛选、About |
| 认证 | 手机号 + 短信验证码、RS256 JWT 双 Token |
| 发帖 | Markdown 编辑器、OSS 预签名直传、五步发布流程 |
| 互动 | 点赞/收藏（Redis Bitmap + Kafka 聚合）、二级评论、通知 |
| 社交 | 关注/粉丝、用户公开主页（`/user/[handle]`） |
| 搜索 | Elasticsearch 全文检索（IK 分词）、联想建议、游标分页 |
| Agent | 自研 ReAct 引擎 + WebSocket 流式对话（需 `LLM_ENABLED=true`） |
| Bangumi | 番剧 API 客户端与 Agent 工具集成 |
| 运维 | Actuator 健康检查（ES/OSS/Bangumi）、OpenAPI 文档（dev 默认开启） |

## 仓库结构

```
apps/web/              Next.js 站点（Sakuga 视觉）
apps/server/           Spring Boot API
  db/                  MySQL schema、migration、seed
docker/                本地依赖说明 + Nginx 生产配置
docker-compose.prod.yml  单机生产 Compose
scripts/dev/           本地启动脚本（读根目录 .env）
scripts/oss/           OSS 正文 seed 与上传脚本
AGENTS.md              开发指南（模块地图、规范、环境变量）
```

## 前置条件

- **JDK 21**、**Node.js 18+**、**Maven 3.9+**
- 本地 Docker 已运行 MySQL / Redis / Kafka / Elasticsearch（见 [docker/README.md](docker/README.md)）
- MySQL 库 **`chtholly`** 已建表并导入种子数据

### 首次初始化

1. 建表：`apps/server/db/schema.sql`，或按 [apps/server/db/README.md](apps/server/db/README.md) 追增量 migration（`V5`–`V10`）
2. 种子数据：`apps/server/db/seed/phase_a_seed.sql`
3. OSS 正文：`node scripts/oss/upload-seed-markdown.mjs`（需 `.env` 中 OSS 凭证）

## 快速开始

### 1. 环境变量

```powershell
copy .env.example .env
# 编辑 .env：MYSQL_PASSWORD、OSS_* 等（勿提交 .env）
```

常用变量见 [.env.example](.env.example)。本地开发默认后端 **8888**（Windows WinNAT 占用 8080，故不用 8080）。

### 2. 基础设施

确认 Docker 容器在跑：`localhost:3306` / `6379` / `9092` / `9200`。详见 [docker/README.md](docker/README.md)。

### 3. 一键启动（Cursor / VS Code 推荐）

**`Ctrl+Shift+P`** → **`Tasks: Run Task`**：

| 任务 | 说明 |
|------|------|
| **Chtholly · 启动全栈（后端 + 前端）** | 开两个专用终端 |
| **Chtholly · 启动后端（验证码看此终端）** | 登录验证码在 `LoggingCodeSender code=` |
| **Chtholly · 启动前端** | http://localhost:3000 |

### 4. 手动启动

**后端**（推荐脚本，会自动加载根目录 `.env`）：

```powershell
.\scripts\dev\start-backend.ps1
```

或：

```powershell
cd apps/server
mvn spring-boot:run
```

**前端**：

```powershell
cd apps/web
npm install
npm run dev
```

| 服务 | 地址 |
|------|------|
| 前端 | http://localhost:3000 |
| 后端 API | http://localhost:8888 |
| 健康检查 | http://localhost:8888/actuator/health |
| Swagger UI | http://localhost:8888/swagger-ui.html（`SPRING_PROFILES_ACTIVE=dev` 或 `SWAGGER_ENABLED=true`） |

前端 `/api/*` 由 Next.js 代理到 `:8888`；RSC/SSR 直连 `API_SERVER_URL`。

## 站点路由

| 路由 | 说明 |
|------|------|
| `/` | 站长 Feed（`SITE_OWNER_USER_ID`，默认 1） |
| `/post/[slug]` | Markdown 详情（OSS `contentUrl`） |
| `/archive` | 按发布时间归档 |
| `/tag/[name]` | 标签筛选 |
| `/search` | 全文搜索 |
| `/about` | 关于页 |
| `/login` | 登录 / 注册 |
| `/write` | 发帖编辑器（需登录） |
| `/user/[handle]` | 用户公开主页 |
| `/agent` | AI 助手对话（需 `LLM_ENABLED=true`） |

站点配置：`apps/web/lib/site.config.ts`。

## 可选功能开关

| 变量 | 默认 | 说明 |
|------|------|------|
| `LLM_ENABLED` | `false` | Agent、RAG、AI 摘要；需 `DEEPSEEK_API_KEY` + `DASHSCOPE_API_KEY` |
| `BANGUMI_ENABLED` | `true` | Bangumi API；国内建议配置 `BANGUMI_HTTP_PROXY` |
| `CANAL_ENABLED` | `false` | Outbox → Canal CDC；本地一般关闭 |
| `SWAGGER_ENABLED` | dev 下 `true` | OpenAPI / Swagger UI |

启用 LLM 时设置 `LLM_ENABLED=true`，并配置 API Key；详见 [apps/server/README.md](apps/server/README.md)。

## 开发文档

| 文档 | 用途 |
|------|------|
| [AGENTS.md](AGENTS.md) | 模块地图、架构决策、编码规范、环境变量（**Coding Agent / 新贡献者首选**） |
| [apps/server/README.md](apps/server/README.md) | 后端模块、编译、冒烟测试 |
| [apps/server/db/README.md](apps/server/db/README.md) | 数据库初始化与 migration |
| [docker/README.md](docker/README.md) | Docker 依赖与生产部署 |

```powershell
# 后端编译
cd apps/server; mvn compile

# 前端构建
cd apps/web; npm run build
```

## 生产部署

```bash
cp .env.prod.example .env
docker compose -f docker-compose.prod.yml up -d --build
```

浏览器访问 `http://<服务器IP>/`；API 经 Nginx 同域 `/api/v1/...` 转发。详见 [docker/README.md](docker/README.md) 生产章节。

## 参考项目（同级目录，不纳入本仓）

- `My_Blog` — Sakuga UI 参考
