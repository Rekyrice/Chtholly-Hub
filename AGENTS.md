# Chtholly Hub — Agent 全局目录与工作契约

> 本文件是仓库级任务的权威入口，适用于整个工作树；更深目录中的 `AGENTS.md` 可以补充该子树的局部规则，但不得放宽本文的安全约束。
>
> 采用渐进阅读：先用本文确定任务边界，只读取与当前任务直接相关的章节和局部规则，不要一次性加载整套知识库。

## 开始任务前

1. 阅读本文件，确认全局安全、Git、编码和验证规则。
2. 在“任务路由”中找到任务类型，阅读对应的稳定知识章节。
3. 进入目标子树后，继续阅读最近的局部 `AGENTS.md` 和应用 README。
4. 检查 `git status`、当前分支、相关测试与用户已有改动，再决定最小实施范围。

## 任务路由

| 任务 | 先读 | 继续深入 |
|------|------|----------|
| 后端业务、API、缓存或事件 | [后端应用入口](apps/server/README.md) | [架构首页](docs/architecture/README.md)、[数据库操作入口](apps/server/db/README.md) |
| 前端页面、组件或交互 | [前端局部规则](apps/web/AGENTS.md) | [前端应用入口](apps/web/README.md)、[架构首页](docs/architecture/README.md) |
| 角色 Agent、工具、上下文或记忆 | [后端应用入口](apps/server/README.md) | [Agent 代码入口](apps/server/src/main/java/com/chtholly/agent/ChthollyAgent.java)、[架构首页](docs/architecture/README.md) |
| 数据库、缓存、搜索、消息或 OSS | [数据库操作入口](apps/server/db/README.md) | [后端应用入口](apps/server/README.md)、[架构首页](docs/architecture/README.md) |
| 本地环境、配置与测试 | [项目快速开始](README.md) | [后端应用入口](apps/server/README.md)、[前端应用入口](apps/web/README.md) |
| 部署、代理或容器 | [Docker 操作入口](docker/README.md) | [脚本入口](scripts/README.md)、[架构首页](docs/architecture/README.md) |
| Git、文档与仓库维护 | 本文件“工作区与 Git 安全” | [知识库首页](docs/README.md)、[脚本入口](scripts/README.md) |

## 系统概览

Chtholly Hub 是 Java 21 + Spring Boot 3.2.4 与 Next.js 16 + Tailwind CSS 4 组成的 Monorepo：

```text
浏览器
  └─ Next.js Web（apps/web）
       └─ Spring Boot API（apps/server）
            ├─ MySQL：业务权威数据
            ├─ Redis：缓存、Token、限流与位图状态
            ├─ Elasticsearch：可降级的全文检索
            ├─ Kafka：计数聚合与 Outbox 异步链路
            └─ 本地文件系统（默认）或 OSS：Markdown 正文与媒体

可选：WebSocket/API → 角色 Agent → LLM、RAG 与站内工具
```

系统边界、同步/异步关系和关键入口统一见[架构首页](docs/architecture/README.md)。

## 全局编码规则

### 后端

- 按 `{module}/api`、`service`、`service/impl`、`mapper`、`model` 组织业务；修改前先读[后端应用入口](apps/server/README.md)。后端局部规则将在 `apps/server/AGENTS.md` 建立后接管更细约束。
- 类级和 public/protected 方法级 Javadoc 使用英文，方法体内只用中文解释非显然的 WHY；魔法数字和非常规逻辑必须注明来源或含义。
- 业务错误使用 `BusinessException` 与明确 HTTP 状态；禁止 `catch (Exception ignored) {}`，每个 catch 至少记录日志。
- 环境相关值通过 `application*.yml` 中的 `${VAR:default}` 注入；可选能力必须有特性开关。
- MyBatis 只允许 `#{param}` 参数化，禁止 `${param}`；实体 ID 使用 `SnowflakeIdGenerator`，禁止新增自增主键。
- TODO/HACK 必须写明跟踪来源或移除条件，不保留注释掉的死代码。

### 前端

- `app/(site)` 默认使用 Server Components 和 ISR；仅在浏览器交互确有需要时加入 `"use client"`。
- Server Component 通过服务模块取数；Client Component 通过 `lib/services/apiClient.ts` 调用 API。
- API 请求与响应类型集中在 `lib/types`，不要在页面中复制接口结构。
- 样式使用 Tailwind 与 `globals.css` 主题 Token，优先复用 `--blog-primary`、`--blog-secondary` 等现有变量。
- 进入前端子树后，以 `apps/web/AGENTS.md` 的 Next.js 版本规则、交互验证和局部约束为准。

## 临时文件与工作区安全

- Agent 创建的临时文件必须放在当前项目内、且已被 Git 忽略的目录，例如 `.codex-tmp/` 或 `.superpowers/`。除非工具无法在项目内运行，不得写入 C 盘或用户主目录。
- 创建项目内临时目录前，先通过 `.gitignore` 或本地 `.git/info/exclude` 确认其已被忽略。任务结束后删除任务专用临时文件并停止临时服务，除非用户要求保留。
- 仓库可能已有用户或其他会话的改动。不得暂存、提交、变基、清理、复制、移动或删除不属于当前任务的文件、分支或 worktree。

## Git ignore 安全

- 仓库忽略规则具有最高优先级。不得使用 `git add -f`，也不得因计划、Skill、模板或交付清单要求提交某文件而绕过忽略规则；只有用户明确授权该具体文件时才可例外。
- 每次提交前，必须审计本次新增且已暂存的文件：

  ```powershell
  git diff --cached --name-only --diff-filter=A | git check-ignore -v --no-index --stdin
  ```

  任何输出都是硬停止条件；逐个取消暂存并修正交付范围，禁止强制添加。
- 每次 push 或创建 PR 前，先 fetch 目标分支，并审计任务新增文件（目标分支不是 `main` 时替换比较基线）：

  ```powershell
  git fetch origin
  git diff --name-only --diff-filter=A origin/main...HEAD | git check-ignore -v --no-index --stdin
  ```

  任何输出都阻止发布。不能只依赖 `git status`、范围检查或 `git ls-files -ci`，因为它们无法可靠区分基线中已跟踪的忽略文件。
- 区分基线已跟踪的忽略文件与本任务新增文件，不修改无关基线文件。若本任务已经发布了被忽略文件，必须从 Agent 自有分支的任务历史中移除，确认审计为空，再用 `--force-with-lease` 更新；仅追加删除提交不能清理历史。

## 并发 worktree 工作流

- 并发开发必须为每个会话使用独立分支和 `<repo>/.worktrees/<task>` 下的独立 worktree；创建前确认 `.worktrees/` 已被忽略，不得直接在共享主工作树实施。
- 创建或发布任务分支前，先 `git fetch origin`，以最新 `origin/main` 为基线，并检查 `git status`、`git log --left-right --cherry-pick origin/main...HEAD` 和 `git diff origin/main...HEAD`，确保只包含任务范围。
- 提交按可独立验证的真实职责边界拆分。push 或 PR 前运行快速测试与任务适用的集成测试。只有在 Agent 自有任务分支有意重写历史后，才允许使用 `--force-with-lease`。
- PR 未关闭前保留任务 worktree。用户确认 PR 已合并或工作已放弃之前，不得合并、删除分支或移除 worktree。
- 合并后先确认 PR 与 CI 状态，执行 `git fetch --prune`，确认主 worktree 与任务 worktree 干净，快进本地主分支，并验证功能树已存在于主分支。对于 squash merge，删除分支前必须比较树等价，因为任务提交不会成为主分支祖先。
- 清理时使用 `git worktree remove`，只在确认 squash merge 树等价后才可 `git branch -D`，随后执行 `git worktree prune`，并确认本地/远端引用、worktree 注册和目录均已消失。
- Windows 删除前必须解析绝对路径并确认目标位于仓库 `.worktrees` 目录内。优先使用 Git 原生命令；先诊断锁，不得对未验证或计算出的路径递归删除，只可处理确认安全的空残留目录。

## 提交规则

- 使用中文 Conventional Commits：`feat:`、`fix:`、`refactor:`、`chore:`、`docs:`、`test:`。
- 分支名保持技术化，功能与修复默认使用 `feat/{description}`、`fix/{description}`；文档分支可使用 `docs/{description}`。
- 分支名、提交信息、代码注释与工程文档只描述技术事实。
- Coding Agent 只提交、不 push；远端推送由项目维护者执行。

## 验证命令

```powershell
# 后端全量测试
cd apps/server
mvn test

# 后端定向测试；PowerShell 中多个类名参数需要整体加引号
mvn -q '-Dtest=ClassATest,ClassBTest' test

# 前端测试与生产构建
cd ../web
npm run test:run
npm run build

# 提交前格式与范围
cd ../..
git diff --check
git status --short
```

按改动风险选择完整验证组合；后端和前端的当前命令分别以[后端应用入口](apps/server/README.md)和[前端应用入口](apps/web/README.md)为准。

## 章节索引

- [项目知识库](docs/README.md)
- [架构首页](docs/architecture/README.md)
- 待建立的架构专章：`backend.md`、`frontend.md`、`agent-system.md`、`data-and-storage.md`、`request-flows.md`
- 待建立的开发章节：`docs/development/README.md`、`configuration.md`、`database.md`、`testing.md`
- 待建立的运维章节：`docs/operations/deployment.md`
- [后端应用入口](apps/server/README.md)
- [前端局部规则](apps/web/AGENTS.md)
- [数据库操作入口](apps/server/db/README.md)
- [Docker 操作入口](docker/README.md)
- [脚本入口](scripts/README.md)

## Chtholly Hub 项目偏好

- 设计文档和实施计划默认使用中文，用户明确要求其他语言时除外。
- 分支名、提交信息、代码注释与工程文档保持严格技术化。
