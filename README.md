# Chtholly Hub

Rekyrice（伊米花）的个人动漫博客，逐步演进为动漫社区。  
Monorepo：Next.js 前端 + Spring Boot 后端。

## 仓库结构

```
apps/web/      Next.js 站点（Sakuga 视觉）
apps/server/   Spring Boot API
docker/        本地依赖说明（实际容器在 D 盘外部目录）
```

## 前置条件

- **JDK 21**、**Node.js 18+**
- 本地 Docker 已运行 MySQL / Redis / Kafka / ES（见 [docker/README.md](docker/README.md)）
- MySQL 库 **chtholly** 已创建并导入 schema

## 快速开始

### 1. 环境变量

```powershell
copy .env.example .env
# 编辑 .env：填入 MYSQL_PASSWORD、OSS 等（勿提交 .env）
```

### 2. 基础设施

确认 Docker 容器在跑（`localhost:3306` / `6379` / `9092` / `9200`）。  
详见 [docker/README.md](docker/README.md)。

### 3. 后端（终端 1）

```powershell
cd apps/server
./mvnw spring-boot:run
```

默认 `http://localhost:8080`

### 4. 前端（终端 2）

```powershell
cd apps/web
npm install
npm run dev
```

默认 `http://localhost:3000`，`/api/v1/*` 代理到后端。

## 开发阶段

| 阶段 | 能力 |
|------|------|
| Phase A | 只读博客：Feed、slug 详情、About / Archive / Tag |
| Phase B | 登录、Markdown 发帖 |
| Phase 3+ | 社区互动、搜索、AI |

## 参考项目（同级目录，不纳入本仓）

- `My_Blog` — Sakuga UI 参考
