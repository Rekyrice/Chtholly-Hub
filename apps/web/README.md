# Chtholly Hub Web

`apps/web` 是 Chtholly Hub 的 Next.js 16 前端，负责公开内容、Hub、Agent 工作区与浮窗、写作、账号、用户主页和 Admin 体验。后端业务规则由 `apps/server` 提供；开发环境通过 Next rewrite 代理 `/api/v1/*` 与 `/uploads/*`，生产环境使用 standalone 输出并由 Nginx 同域反代。

## 先读

- [前端局部规则](AGENTS.md)：Next.js 16、本地文档、Server/Client、service/type、样式与验证约束。
- [前端架构](../../docs/architecture/frontend.md)：用户路径、真实路由、组件、API、Live2D 与测试地图。
- [Agent 系统](../../docs/architecture/agent-system.md)：Agent WebSocket、Core、上下文、记忆和扩展边界。

## 目录

| 目录 | 职责 |
|------|------|
| `app/(site)` | 公开站点、Hub、Agent、写作、账号、用户与 Admin 路由 |
| `app/styles` | 页面/领域样式，主题源在 `app/globals.css` |
| `components/site` | 站点、Hub、账号与 Admin 业务组件 |
| `components/agent` | Agent workspace、浮窗、会话、WebSocket 与 Live2D |
| `components/ui` | 通用基础组件 |
| `components/write` | Markdown 写作子组件 |
| `lib/services` | API 传输层和领域 service |
| `lib/types` | API 与共享领域类型 |
| `lib/auth` | 浏览器登录态与 token 生命周期 |

## 环境变量

变量合同统一记录在仓库根目录 [`.env.example`](../../.env.example)。仓库的 PowerShell 启动脚本会读取根 `.env`；Next.js 自身只从应用目录或进程环境取值，裸 `npm run dev` 与 `npm run build` 不会自动读取父目录的根 `.env`。

| 变量 | 用途 | 缺省行为 |
|------|------|----------|
| `API_SERVER_URL` | Next Server Component 与开发 rewrite 访问 Spring Boot | `http://localhost:8888` |
| `NEXT_PUBLIC_API_SERVER_URL` | 浏览器需要显式知道 API origin 时使用，也可推导 WebSocket | 浏览器 API 通常走同域相对路径 |
| `NEXT_PUBLIC_WS_URL` | Agent WebSocket origin | 回退到 `NEXT_PUBLIC_API_SERVER_URL`，再回退当前主机 `:8888` |
| `NEXT_PUBLIC_SITE_URL` | canonical、Open Graph 等站点 URL | 本地站点地址 |
| `NEXT_PUBLIC_OSS_PUBLIC_URL` | Next Image 允许的 OSS 公网来源 | 仅保留内置开发 OSS pattern |
| `NEXT_PUBLIC_OWNER_USER_ID` | 站点所有者展示相关配置 | 未设置时使用代码缺省逻辑 |

## 本地启动与验证

在仓库根目录准备 `.env` 并先启动后端。首次安装依赖：

```bash
cd apps/web
npm install
```

Windows/PowerShell 推荐回到仓库根目录运行脚本；它先加载根 `.env`，再进入 `apps/web` 启动 Next.js：

```powershell
.\scripts\dev\start-frontend.ps1
```

其他系统或需要裸 npm 命令时，把前端变量写入被 Git 忽略的 `apps/web/.env.local`，或在终端/CI 中显式注入，再进入 `apps/web` 运行 `npm run dev`。前端默认访问 `http://localhost:3000`，Spring Boot 默认访问 `http://localhost:8888`。

```bash
# 一次性运行全部 Vitest
npm run test:run

# 生产构建；先通过 apps/web/.env.local 或终端/CI 注入所需前端变量
npm run build

# 运行已构建的 standalone-compatible Next 服务
npm run start
```

修改页面或组件前先查阅 `node_modules/next/dist/docs/` 中对应的 Next.js 16 本地文档；本 README 只记录 Chtholly Hub Web 的项目入口，不替代框架文档。
