<!-- BEGIN:nextjs-agent-rules -->
# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` before writing any code. Heed deprecation notices.
<!-- END:nextjs-agent-rules -->

# Chtholly Hub Web 局部规则

本文件适用于 `apps/web`。仓库级安全与提交约束仍以根目录 [`AGENTS.md`](../../AGENTS.md) 为准，本文件只定义前端局部规则。

## 开始前

1. 先读[前端架构](../../docs/architecture/frontend.md)，按用户路径找到真实路由、组件、service、type 和测试入口。
2. 修改 Agent UI、WebSocket、Live2D 或页面上下文时，再读[Agent 系统](../../docs/architecture/agent-system.md)。
3. 这是 Next.js 16；写代码前必须查阅 `node_modules/next/dist/docs/` 中与当前 API 对应的本地文档，不依赖训练记忆或通用 Next.js 教程。

## 组件与数据边界

- `app/(site)` 默认使用 Server Components；公共读取优先由页面直接调用 `lib/services/*Service.ts`。需要 ISR 时由路由显式声明 `revalidate`。
- 仅在浏览器事件、hooks、`localStorage`、WebSocket、Pixi/Live2D 或认证写操作确有需要时加入 `"use client"`，并把 Client 边界压到最小交互组件。
- API 传输统一经 `lib/services/apiClient.ts`，领域 endpoint 放在 `lib/services`；浏览器走相对路径与 rewrite，RSC 走 `API_SERVER_URL`。不要在组件内另写 fetch/刷新协议。
- 裸 `npm run dev` 与 `npm run build` 不会读取父目录的根 `.env`；本地裸 npm 使用被忽略的 `apps/web/.env.local` 或进程环境，PowerShell 启动则从仓库根运行 `scripts/dev/start-frontend.ps1`。
- 请求/响应和共享领域类型放在 `lib/types`；认证持久化与订阅放在 `lib/auth`。不要在页面或组件中复制类型与 token 逻辑。
- 通用交互组件放 `components/ui`，站点业务组件放 `components/site`，Agent 放 `components/agent`，写作子组件放 `components/write`。

## 样式与交互

- 主题合同以 `app/globals.css` 当前存在的 Token 为准，优先复用 `--color-*`、`--color-primary` 与 `--blog-primary`。不要引用并不存在的 `--blog-secondary`，也不要复制一套硬编码主题色。
- 页面样式放在 `app/styles`，基础变体优先复用 `components/ui`；新增交互必须覆盖键盘、焦点、loading、空态、失败态以及必要的 `aria-*`。
- Live2D/Pixi 只能在 Client Component 中延迟加载；改动必须清理 ticker、监听器、音频、canvas 和模型资源，并保留初始化失败降级。

## 验证

- 测试使用 Vitest + Testing Library，测试文件与源码邻近；行为变更同时覆盖成功、失败和边界状态。
- 定向测试通过后运行：

  ```bash
  npm run test:run
  npm run build
  ```

- `build` 用于验证 Next.js 路由、Server/Client 边界、类型与 standalone 生产输出，不能只凭单测宣布完成。
