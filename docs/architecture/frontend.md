# 前端架构

## 阅读时机

新增或修改页面、导航、交互、API 调用、类型、主题、Live2D 或前端测试前阅读本章。进入 `apps/web` 后还必须遵循[前端局部规则](../../apps/web/AGENTS.md)；涉及 Agent WebSocket 事件、上下文或后端能力时继续读[Agent 系统](agent-system.md)。

## 读完能回答的问题

- 一个用户路径对应哪个路由文件、组件与 service/type？
- 哪些页面应保持 Server Component，哪些交互必须进入 Client Component？
- `apiClient` 在浏览器与 RSC 中如何选择 API 地址，认证刷新适用于哪一侧？
- 共享样式 Token、Live2D 运行时和测试入口在哪里？
- 修改 Hub、Agent、写作或 Admin 时最小验证范围是什么？

## 用户路径与真实路由

路由均位于 [`app/(site)`](<../../apps/web/app/(site)>):

| 用户路径 | 路由入口 | 主要组件与数据入口 |
|----------|----------|--------------------|
| 公开落地页、文章与发现 | [`/`](<../../apps/web/app/(site)/page.tsx>)、[`/archive`](<../../apps/web/app/(site)/archive/page.tsx>)、[`/search`](<../../apps/web/app/(site)/search/page.tsx>)、[`/tag/[name]`](<../../apps/web/app/(site)/tag/[name]/page.tsx>)、[`/post/[slug]`](<../../apps/web/app/(site)/post/[slug]/page.tsx>)、[`/about`](<../../apps/web/app/(site)/about/page.tsx>)、[`/chtholly`](<../../apps/web/app/(site)/chtholly/page.tsx>) | [`PostCard`](../../apps/web/components/site/PostCard.tsx)、[`CommentSection`](../../apps/web/components/site/CommentSection.tsx)、[`postService`](../../apps/web/lib/services/postService.ts)、[`searchService`](../../apps/web/lib/services/searchService.ts) |
| Hub | [`/hub`](<../../apps/web/app/(site)/hub/page.tsx>) | [`HubDiscovery`](../../apps/web/components/site/HubDiscovery.tsx)、[`HomeFeed`](../../apps/web/components/site/HomeFeed.tsx)、[`Sidebar`](../../apps/web/components/site/Sidebar.tsx)、`searchService.hubFeed` 与 `agentService.recentExperiences` |
| Agent | [`/agent`](<../../apps/web/app/(site)/agent/page.tsx>) | [`AgentWorkspace`](../../apps/web/components/agent/AgentWorkspace.tsx)、[`AgentChatProvider`](../../apps/web/components/agent/AgentChatProvider.tsx)、[`useAgentWebSocket`](../../apps/web/components/agent/hooks/useAgentWebSocket.ts) |
| 写作 | [`/write`](<../../apps/web/app/(site)/write/page.tsx>) | [`MarkdownToolbar`](../../apps/web/components/write/MarkdownToolbar.tsx)、[`TagAutocomplete`](../../apps/web/components/write/TagAutocomplete.tsx)、`postService`、`storageService`、`postAiService` |
| 登录与账号恢复 | [`/login`](<../../apps/web/app/(site)/login/page.tsx>)、[`/reset-password`](<../../apps/web/app/(site)/reset-password/page.tsx>) | [`authService`](../../apps/web/lib/services/authService.ts)、[`auth-store`](../../apps/web/lib/auth/auth-store.ts)、[`tokens`](../../apps/web/lib/auth/tokens.ts) |
| 设置、资料与用户主页 | [`/settings`](<../../apps/web/app/(site)/settings/page.tsx>)、[`/profile/edit`](<../../apps/web/app/(site)/profile/edit/page.tsx>)、[`/user/[handle]`](<../../apps/web/app/(site)/user/[handle]/page.tsx>) | [`ProfileEditForm`](../../apps/web/components/site/ProfileEditForm.tsx)、[`UserTabs`](../../apps/web/components/site/UserTabs.tsx)、[`profileService`](../../apps/web/lib/services/profileService.ts)、[`userService`](../../apps/web/lib/services/userService.ts) |
| Admin | [`/admin`](<../../apps/web/app/(site)/admin/page.tsx>)、[`/admin/posts`](<../../apps/web/app/(site)/admin/posts/page.tsx>)、[`/admin/users`](<../../apps/web/app/(site)/admin/users/page.tsx>)、[`/admin/dead-letter`](<../../apps/web/app/(site)/admin/dead-letter/page.tsx>)、[`/admin/traces`](<../../apps/web/app/(site)/admin/traces/page.tsx>) | [`AdminShell`](../../apps/web/components/site/AdminShell.tsx)、[`AdminPostsTable`](../../apps/web/components/site/AdminPostsTable.tsx)、[`AdminUsersTable`](../../apps/web/components/site/AdminUsersTable.tsx)、[`adminService`](../../apps/web/lib/services/adminService.ts)、[`traceService`](../../apps/web/lib/services/traceService.ts) |

[`app/(site)/layout.tsx`](<../../apps/web/app/(site)/layout.tsx>) 用 [`SiteChrome`](../../apps/web/components/site/SiteChrome.tsx) 提供站点外壳，并挂载延迟加载的 Agent 运行时。路由组不出现在 URL 中。

## 目录职责

| 目录 | 责任 |
|------|------|
| [`app/(site)`](<../../apps/web/app/(site)>) | App Router 页面、布局、错误/加载态与路由级元数据 |
| [`components/site`](../../apps/web/components/site) | 公开站点、Hub、账号和 Admin 的领域 UI |
| [`components/agent`](../../apps/web/components/agent) | Agent workspace、浮窗、会话、WebSocket 状态与 Live2D UI |
| [`components/ui`](../../apps/web/components/ui) | 无业务归属的 Button、Badge、Skeleton、EmptyState 等基础组件 |
| [`components/write`](../../apps/web/components/write) | Markdown 写作流程的编辑器子组件 |
| [`lib/services`](../../apps/web/lib/services) | 按后端资源拆分的 API 调用；公共传输行为集中在 `apiClient.ts` |
| [`lib/types`](../../apps/web/lib/types) | API 请求/响应和前端共享领域类型 |
| [`lib/auth`](../../apps/web/lib/auth) | 浏览器 token 持久化、有效期判断与 React 登录态订阅 |

组件只负责展示和交互时，不要把 endpoint、token 解析或重复的响应类型搬进组件文件。

## Server Component 与 Client 交互边界

App Router 默认是 Server Component。公开读取路径优先保持服务端渲染：例如 [`/hub`](<../../apps/web/app/(site)/hub/page.tsx>) 是 `async` 页面，声明 `revalidate = 60`，直接调用 service 并把数据传给交互组件。文章详情、归档、标签、搜索和用户主页也应先判断能否沿用这一模式。

以下需求才需要把边界下沉到 Client Component：

- 浏览器事件、表单状态、`useEffect`、`localStorage` 或 WebSocket；
- 依赖登录 token 的即时写操作；
- Live2D/Pixi、动态导入或其他仅浏览器可用运行时。

[`/write`](<../../apps/web/app/(site)/write/page.tsx>)、[`/login`](<../../apps/web/app/(site)/login/page.tsx>) 和 [`/settings`](<../../apps/web/app/(site)/settings/page.tsx>) 因上述交互明确使用 `"use client"`。不要为了复用一个交互按钮把整张可服务端渲染的页面改成 Client Component；优先保留服务端页面，把最小交互岛放进 `components/site`、`components/agent` 或 `components/write`。

## API 与认证行为

[`apiClient.ts`](../../apps/web/lib/services/apiClient.ts) 是所有 service 的传输层：

- 浏览器使用相对 `/api/v1/*`，开发环境由 [`next.config.ts`](../../apps/web/next.config.ts) rewrite 到 `API_SERVER_URL`，生产环境由 Nginx 同域反代。
- Server Component 中没有 `window`，`apiClient` 直接请求 `API_SERVER_URL`，缺省 `http://localhost:8888`；不要在 RSC 中依赖浏览器相对 URL。
- token 缺省从 `localStorage` 读取，因此 RSC 公共读取不会自动带浏览器登录态。需要认证的页面当前使用客户端登录态与 service 调用，不要误认为 RSC 能读取 `localStorage`。
- 浏览器非幂等请求从 `XSRF-TOKEN` cookie 附加 `X-XSRF-TOKEN`；401 时可用 refresh token 做一次并发去重刷新。失败后清理登录态；幂等请求会再以匿名身份重试，写请求不会匿名重放。
- 错误统一为 `ApiError`，并限制/净化后端或代理错误文本；service 与组件不应各自复制一套 fetch 错误协议。

请求与响应类型放在 [`lib/types`](../../apps/web/lib/types)，例如文章用 [`post.ts`](../../apps/web/lib/types/post.ts)、Agent 用 [`agent.ts`](../../apps/web/lib/types/agent.ts)、Admin 用 [`admin.ts`](../../apps/web/lib/types/admin.ts)。新增资源时按相同名称拆 service/type，并为 `apiClient` 边界行为补测试。

## Agent 路径

独立 [`/agent`](<../../apps/web/app/(site)/agent/page.tsx>) 使用 workspace；其他已登录站点页面可由 [`DeferredAgentRuntime`](../../apps/web/components/agent/DeferredAgentRuntime.tsx) 延迟、仅客户端加载 [`AuthenticatedAgentRuntime`](../../apps/web/components/agent/AuthenticatedAgentRuntime.tsx)，后者按路由策略挂载主动通知和浮动 Agent，避免 Pixi/Agent 代码进入服务端渲染。

[`wsUrl.ts`](../../apps/web/lib/agent/wsUrl.ts) 先经 `POST /api/v1/agent/ws-ticket` 换取 ticket，再构造 `/api/v1/agent/ws?ticket=...`；URL 优先读取 `NEXT_PUBLIC_WS_URL`，其次把 `NEXT_PUBLIC_API_SERVER_URL` 转换为 ws/wss，最后在浏览器回退到当前主机的 `:8888`。消息、重连和页面上下文集中在 [`useAgentWebSocket`](../../apps/web/components/agent/hooks/useAgentWebSocket.ts)，事件协议变更必须同步核对[Agent 系统主链](agent-system.md#一次对话的主链)。

## 主题与 Live2D

全局主题源是 [`app/globals.css`](../../apps/web/app/globals.css)。优先复用 `--color-sky`、`--color-violet`、`--color-sunset`、`--color-surface`、`--color-text`、`--color-border`、`--color-primary` 与 `--blog-primary` 等现有 Token；暗色值由 `[data-theme="dark"]` 覆盖。源码当前没有 `--blog-secondary`，不要引用或新增同名变量来假装已有设计合同。

页面级样式位于 [`app/styles`](../../apps/web/app/styles)，应以 Token 连接主题而不是复制颜色常量。Tailwind CSS 4 的 theme 映射也在 `globals.css`，基础组件复用 [`components/ui`](../../apps/web/components/ui)。

Live2D 常量与静态路径在 [`lib/live2d/constants.ts`](../../apps/web/lib/live2d/constants.ts)，模型位于 `public/live2d/chtholly`。[`ChthollyLive2D`](../../apps/web/components/agent/ChthollyLive2D.tsx) 仅在客户端动态加载 Cubism 2 脚本、Pixi 7 与 `pixi-live2d-display`，并负责 ticker、指针、音频和销毁；修改时必须验证失败降级和卸载清理，不能在 Server Component 顶层导入浏览器运行时。

## 测试与构建

[`package.json`](../../apps/web/package.json) 定义当前命令：

```bash
cd apps/web
npm run test:run
npm run build
```

Vitest 使用 [`vitest.config.ts`](../../apps/web/vitest.config.ts) 的 `jsdom` 环境与 [`test/setup.ts`](../../apps/web/test/setup.ts) 的 Testing Library matcher。测试与源码邻近，例如 [`AgentChatPanel.test.tsx`](../../apps/web/components/agent/AgentChatPanel.test.tsx)、[`PostCard.test.tsx`](../../apps/web/components/site/PostCard.test.tsx)、[`apiClient.test.ts`](../../apps/web/lib/services/apiClient.test.ts) 和 [`admin/traces/page.test.tsx`](<../../apps/web/app/(site)/admin/traces/page.test.tsx>)。

| 改动 | 最小定向入口 | 收尾验证 |
|------|--------------|----------|
| Server/Client 边界或站点外壳 | [`layout.test.tsx`](<../../apps/web/app/(site)/layout.test.tsx>)、[`SiteChrome.test.tsx`](../../apps/web/components/site/SiteChrome.test.tsx) | 全量 `test:run` + `build` |
| Agent UI/WebSocket/延迟加载 | `components/agent/*.test.tsx`、[`agentRuntimePolicy.test.ts`](../../apps/web/components/agent/agentRuntimePolicy.test.ts) | 全量 `test:run` + `build` |
| 写作流程 | [`write/page.test.tsx`](<../../apps/web/app/(site)/write/page.test.tsx>) | 全量 `test:run` + `build` |
| API/认证 | [`apiClient.test.ts`](../../apps/web/lib/services/apiClient.test.ts)、[`authService.test.ts`](../../apps/web/lib/services/authService.test.ts)、[`auth-store.test.ts`](../../apps/web/lib/auth/auth-store.test.ts) | 全量 `test:run` + `build` |
| Admin | [`admin/traces/page.test.tsx`](<../../apps/web/app/(site)/admin/traces/page.test.tsx>) 与相关组件测试 | 全量 `test:run` + `build` |

生产构建同时验证 Next.js 路由、Server/Client 边界、类型检查和 standalone 输出，不能用单测代替。
