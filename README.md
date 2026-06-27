# Chtholly Hub

Rekyrice 的个人动漫博客，逐步演进为动漫社区。（中文名「依米花」为虚构花名，寓意见站内 About 页。）  
Monorepo：Next.js 前端 + Spring Boot 后端。

## 仓库结构

```
apps/web/           Next.js 站点（Sakuga 视觉）
apps/server/        Spring Boot API
  db/               MySQL：schema、migration、seed（见 apps/server/db/README.md）
docker/             本地依赖说明（实际容器在外部目录）
scripts/dev/        本地启动脚本
scripts/oss/        OSS 正文 seed 与上传脚本
```

## 前置条件

- **JDK 21**、**Node.js 18+**
- 本地 Docker 已运行 MySQL / Redis / Kafka / ES（见 [docker/README.md](docker/README.md)）
- MySQL 库 **chtholly** 已建表并导入 Phase A 种子（见下方）

### 首次初始化数据库与正文

1. 建表：`apps/server/db/schema.sql`（步骤见 [apps/server/db/README.md](apps/server/db/README.md)）
2. 种子数据：`apps/server/db/seed/phase_a_seed.sql`
3. OSS 正文：`node scripts/oss/upload-seed-markdown.mjs`

## 快速开始

### 1. 环境变量

```powershell
copy .env.example .env
# 编辑 .env：填入 MYSQL_PASSWORD、OSS 等（勿提交 .env）
```

### 2. 基础设施

确认 Docker 容器在跑（`localhost:3306` / `6379` / `9092` / `9200`）。  
详见 [docker/README.md](docker/README.md)。

### 3. 一键启动（Cursor / VS Code 推荐）

按 **`Ctrl+Shift+P`** → 输入 **`Tasks: Run Task`** → 选择：

| 任务 | 说明 |
|------|------|
| **Chtholly · 启动全栈（后端 + 前端）** | 开两个专用终端 |
| **Chtholly · 启动后端（验证码看此终端）** | 登录验证码在 `LoggingCodeSender code=` |
| **Chtholly · 启动前端** | http://localhost:3000 |

或在终端面板 **`+` 旁的下拉箭头** → 选 **Chtholly · 后端（验证码日志）** / **Chtholly · 前端**。

### 4. 手动启动（可选）

**后端：**

```powershell
.\scripts\dev\start-backend.ps1
```

**前端：**

```powershell
.\scripts\dev\start-frontend.ps1
```

默认后端 `http://localhost:8888`，前端 `http://localhost:3000`。

## 开发阶段

| 阶段 | 能力 | 状态 |
|------|------|------|
| Phase A | 只读博客：Feed、slug 详情、About / Archive / Tag | ✅ 已实现 |
| Phase B | 登录、Markdown 发帖 | 待开发 |
| Phase 3+ | 社区互动、搜索、AI | 待开发 |

### Phase A 页面

| 路由 | 说明 |
|------|------|
| `/` | Rekyrice Feed（`ownerUserId=1`） |
| `/post/[slug]` | Markdown 详情（OSS `contentUrl`） |
| `/about` | 关于页 |
| `/archive` | 按发布时间归档 |
| `/tag/[name]` | 标签筛选，如 `/tag/动漫` |

前端站点配置见 `apps/web/lib/site.config.ts`；服务端 API 请求在 Node 环境直连 `localhost:8888`。

## 参考项目（同级目录，不纳入本仓）

- `My_Blog` — Sakuga UI 参考
