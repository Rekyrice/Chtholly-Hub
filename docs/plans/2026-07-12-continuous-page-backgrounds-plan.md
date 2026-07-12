# Chtholly Hub 全页连续背景实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为每个指定站点页面建立唯一固定全页背景，保留透明刘海结构，并让 Hub 三张背景与三条打字机文案同步轮换。

**Architecture:** `SiteChrome` 成为普通页面背景的唯一所有者，`RoutePageBackground` 在刘海和正文后方固定铺满视口；`SiteHeader` 不再接收或渲染图片，只负责标题、打字机、粒子和局部蒙版。Hub 复用打字机已有索引驱动同一个全页背景组件交叉淡入，Landing 保持独立全屏结构，404 通过显式视觉覆盖接入 `SiteChrome`，Agent 与 Chtholly 保持不变。

**Tech Stack:** Next.js 16、React 19、TypeScript、Tailwind CSS 4、Vitest、Testing Library、Sharp、Playwright

---

## 文件职责

- `apps/web/lib/route-visuals.ts`：页面背景类型、正式资源路径、路由匹配和 404 视觉常量。
- `apps/web/components/site/RoutePageBackground.tsx`：唯一全页背景渲染器和 Hub 交叉淡入层。
- `apps/web/components/site/SiteChrome.tsx`：背景所有权、Hub 当前索引、404 视觉覆盖和页面外壳顺序。
- `apps/web/components/site/SiteHeader.tsx`：无图片刘海结构，向外报告当前打字机索引。
- `apps/web/components/site/HeroTypewriter.tsx`：展示文字并转发当前文案索引。
- `apps/web/lib/hooks/useTypewriterSequence.ts`：打字机文本和索引的唯一时序源。
- `apps/web/app/styles/navbar.css`：刘海蒙版、文字、粒子和局部暗色层。
- `apps/web/app/styles/route-visuals.css`：全页背景、交叉淡入、正文透明表面和降级规则。
- `apps/web/app/(site)/page.tsx`、`apps/web/app/styles/landing.css`：Landing 正式背景及分享元数据。
- `apps/web/app/not-found.tsx`、`apps/web/app/(site)/not-found.tsx`、`apps/web/app/styles/not-found.css`：404 全页背景覆盖和透明内容层。
- `apps/web/public/images/site/backgrounds/*.webp`：普通页面正式背景资源。
- `apps/web/public/images/landing/home.webp`：Landing 正式背景资源。

### Task 1: 锁定路由与单背景配置契约

**Files:**
- Modify: `apps/web/lib/route-visuals.test.ts`
- Modify: `apps/web/lib/route-visuals.ts`

- [ ] **Step 1: 写失败测试，声明每个路由的正式背景资源**

在 `route-visuals.test.ts` 中把现有单图表改为下列映射，并断言普通配置不再包含独立 Hero 图片：

```ts
const routeCases = [
  ["/hub", "hub", ["hub-01.webp", "hub-02.webp", "hub-03.webp"]],
  ["/search", "search", ["search.webp"]],
  ["/write", "write", ["write.webp"]],
  ["/login", "login", ["login.webp"]],
  ["/reset-password/token", "reset-password", ["reset-password.webp"]],
  ["/about", "about", ["about.webp"]],
  ["/user/alice", "user", ["user.webp"]],
  ["/profile/edit", "profile", ["search.webp"]],
  ["/settings", "settings", ["settings.webp"]],
  ["/archive", "archive", ["archive.webp"]],
  ["/tag/typescript", "tag", ["tag.webp"]],
  ["/post/example", "post", ["post.webp"]],
  ["/admin/posts", "admin", ["admin.webp"]],
] as const;
```

同时断言 `NOT_FOUND_VISUAL.images` 为 `not-found.webp`，`/agent`、`/chtholly` 和 `/` 不进入普通路由配置。

- [ ] **Step 2: 运行测试并确认失败原因来自旧类型和旧映射**

Run:

```powershell
cd apps/web
npm run test:run -- lib/route-visuals.test.ts
```

Expected: FAIL，提示 `images`、`NOT_FOUND_VISUAL` 或新的路由 id 尚不存在。

- [ ] **Step 3: 将配置收敛为页面级多图结构**

在 `route-visuals.ts` 中使用以下核心类型，删除 `SITE_HEADER_BACKGROUND`：

```ts
export type PageVisualBackground = {
  readonly images: readonly string[];
  readonly positionDesktop: string;
  readonly positionMobile: string;
  readonly overlayAlpha: number;
  readonly blurPx: number;
  readonly saturate: number;
};

export type RouteVisualConfig = {
  readonly id: string;
  readonly page: PageVisualBackground;
};
```

分别拆分 `login` 与 `reset-password`、`user` 与 `profile`，新增 `/admin` 映射，并导出冻结的 `NOT_FOUND_VISUAL`。Hub 的 `images` 按 `hub-01.webp`、`hub-02.webp`、`hub-03.webp` 顺序配置；其他页面只有一项。

- [ ] **Step 4: 运行路由配置测试**

Run: `npm run test:run -- lib/route-visuals.test.ts`

Expected: 新路由映射、冻结图和排除规则断言通过；公开文件存在性断言仍因 Task 2 尚未生成资源而失败，并在 Task 2 转绿。

- [ ] **Step 5: 暂存职责边界但不提交缺失资源的中间状态**

先保留修改，完成 Task 2 资源生成后与资源一起提交，避免产生引用不存在文件的提交。

### Task 2: 生成正式网页背景资源

**Files:**
- Create: `apps/web/public/images/site/backgrounds/hub-01.webp`
- Create: `apps/web/public/images/site/backgrounds/hub-02.webp`
- Create: `apps/web/public/images/site/backgrounds/hub-03.webp`
- Create: `apps/web/public/images/site/backgrounds/search.webp`
- Create: `apps/web/public/images/site/backgrounds/write.webp`
- Create: `apps/web/public/images/site/backgrounds/login.webp`
- Create: `apps/web/public/images/site/backgrounds/reset-password.webp`
- Create: `apps/web/public/images/site/backgrounds/about.webp`
- Create: `apps/web/public/images/site/backgrounds/user.webp`
- Create: `apps/web/public/images/site/backgrounds/settings.webp`
- Create: `apps/web/public/images/site/backgrounds/archive.webp`
- Create: `apps/web/public/images/site/backgrounds/tag.webp`
- Create: `apps/web/public/images/site/backgrounds/post.webp`
- Create: `apps/web/public/images/site/backgrounds/not-found.webp`
- Create: `apps/web/public/images/site/backgrounds/admin.webp`
- Create: `apps/web/public/images/landing/home.webp`
- Delete after reference removal: obsolete files under `apps/web/public/images/site/backgrounds/`

- [ ] **Step 1: 在已忽略的临时目录创建一次性 Sharp 脚本**

先用 `git check-ignore -v .codex-tmp/process-backgrounds.mjs` 确认临时路径受忽略。脚本建立下列源到目标映射：

```js
const assets = [
  ["1031784.jpg", "site/backgrounds/hub-01.webp", null],
  ["1031785.jpg", "site/backgrounds/hub-02.webp", null],
  ["1031791.jpg", "site/backgrounds/hub-03.webp", null],
  ["1782742256579.jpg", "site/backgrounds/search.webp", 2560],
  ["1782742259461.jpg", "site/backgrounds/write.webp", 2560],
  ["1782742258401.jpg", "site/backgrounds/login.webp", 2560],
  ["806826.png", "site/backgrounds/reset-password.webp", 2560],
  ["1782742257283.jpg", "site/backgrounds/about.webp", 2560],
  ["1782742253982.jpg", "site/backgrounds/user.webp", 2560],
  ["wallhaven-6q78ew.png", "site/backgrounds/settings.webp", 2560],
  ["1031747.jpg", "site/backgrounds/archive.webp", 2560],
  ["909347.jpg", "site/backgrounds/tag.webp", 2560],
  ["806829.png", "site/backgrounds/post.webp", 2560],
  ["1782742258639.jpg", "site/backgrounds/not-found.webp", 2560],
  ["1782742255961.png", "site/backgrounds/admin.webp", 2560],
  ["1031735.jpg", "landing/home.webp", 2560],
];
```

Hub 三张只转换格式和压缩，不改变像素尺寸；其余图片仅在长边超过 2560 时等比缩小。统一输出 WebP `quality: 82, effort: 5`，不做内容裁切。

- [ ] **Step 2: 运行脚本并打印每个输出的尺寸与字节数**

Run: `node .codex-tmp/process-backgrounds.mjs`

Expected: 16 个输出全部生成；Hub 输出尺寸分别保持 2047×1447、2456×1736、2456×1736。

- [ ] **Step 3: 删除临时脚本并移除已无引用的旧正式背景**

删除 `.codex-tmp/process-backgrounds.mjs`。只在 `git grep` 确认无引用后删除以下旧资源：

```text
about-community.webp
archive-hall.webp
auth-arrival.webp
hub-content.webp
hub-hero.webp
post-ruins.webp
profile-personal.webp
search-content.webp
settings-calm.webp
tag-trace.webp
write-workspace.webp
```

保留 `apps/web/public/images/landing/default.jpg`，因为 Chtholly 房间仍使用它。

- [ ] **Step 4: 运行路由配置测试和忽略审计**

Run:

```powershell
npm run test:run -- lib/route-visuals.test.ts
git diff --cached --name-only --diff-filter=A | git check-ignore -v --no-index --stdin
```

Expected: 路由测试 PASS；审计无输出。

- [ ] **Step 5: 提交正式资源和路由配置**

```powershell
git add -- apps/web/lib/route-visuals.ts apps/web/lib/route-visuals.test.ts apps/web/public/images/site/backgrounds apps/web/public/images/landing/home.webp
git diff --cached --name-only --diff-filter=A | git check-ignore -v --no-index --stdin
git diff --cached --check
git commit -m "feat: 配置页面全页背景资源"
```

### Task 3: 让 Hub 背景索引复用打字机时序

**Files:**
- Modify: `apps/web/lib/hooks/useTypewriterSequence.ts`
- Modify: `apps/web/components/site/HeroTypewriter.tsx`
- Modify: `apps/web/components/site/SiteHeader.tsx`
- Create: `apps/web/components/site/HeroTypewriter.test.tsx`
- Modify: `apps/web/components/site/SiteHeader.test.tsx`

- [ ] **Step 1: 写失败测试，要求打字机暴露当前索引**

为 `HeroTypewriter` 增加回调测试：

```tsx
const onLineChange = vi.fn();
render(<HeroTypewriter quotes={["a", "b", "c"]} onLineChange={onLineChange} />);
expect(onLineChange).toHaveBeenLastCalledWith(0);
```

推进假计时器完成第一条的输入、停留和擦除后，断言回调收到 `1`。在 `SiteHeader.test.tsx` 中断言 `SiteHeader` 转发 `onQuoteChange`，同时不再设置 `--site-header-image`。

- [ ] **Step 2: 运行测试并确认旧 API 失败**

Run:

```powershell
npm run test:run -- components/site/HeroTypewriter.test.tsx components/site/SiteHeader.test.tsx
```

Expected: FAIL，提示回调属性或索引不存在。

- [ ] **Step 3: 返回并转发唯一索引**

将 hook 返回值改为：

```ts
return { text, index };
```

`HeroTypewriter` 增加可选回调并在索引改变时通知：

```ts
type HeroTypewriterProps = {
  quotes: readonly string[];
  onLineChange?: (index: number) => void;
};

const { text, index } = useTypewriterSequence(quotes);
useEffect(() => onLineChange?.(index), [index, onLineChange]);
```

`SiteHeader` 删除 `background` 属性和图片 CSS 变量，只保留 `onQuoteChange?: (index: number) => void` 并传给 `HeroTypewriter`。

- [ ] **Step 4: 运行打字机和刘海测试**

Run: `npm run test:run -- components/site/HeroTypewriter.test.tsx components/site/SiteHeader.test.tsx`

Expected: PASS；索引按 `0 → 1` 变化，刘海不拥有图片变量。

- [ ] **Step 5: 提交时序职责**

```powershell
git add -- apps/web/lib/hooks/useTypewriterSequence.ts apps/web/components/site/HeroTypewriter.tsx apps/web/components/site/SiteHeader.tsx apps/web/components/site/HeroTypewriter.test.tsx apps/web/components/site/SiteHeader.test.tsx
git diff --cached --name-only --diff-filter=A | git check-ignore -v --no-index --stdin
git diff --cached --check
git commit -m "refactor: 复用打字机索引驱动背景"
```

### Task 4: 建立唯一全页背景所有权

**Files:**
- Modify: `apps/web/components/site/RoutePageBackground.tsx`
- Modify: `apps/web/components/site/RoutePageBackground.test.tsx`
- Modify: `apps/web/components/site/SiteChrome.tsx`
- Modify: `apps/web/components/site/SiteChrome.test.tsx`
- Modify: `apps/web/app/styles/route-visuals.css`
- Modify: `apps/web/app/styles/navbar.css`
- Modify: `apps/web/test/route-visual-style.test.ts`

- [ ] **Step 1: 写失败测试，锁定背景层顺序和 Hub 激活索引**

`RoutePageBackground.test.tsx` 断言三张图片都位于同一个固定背景容器，只有当前索引带激活类：

```tsx
render(<RoutePageBackground background={hubBackground} activeIndex={1} />);
expect(screen.getAllByTestId("route-page-background-image")).toHaveLength(3);
expect(screen.getAllByTestId("route-page-background-image")[1]).toHaveClass(
  "route-page-background__image--active",
);
```

`SiteChrome.test.tsx` 断言背景在 `SiteHeader` 之前，Hub 文案索引回调会改变背景的 `activeIndex`；Search 等静态页面固定为 0；Agent、Chtholly 无普通背景。

- [ ] **Step 2: 运行测试并确认旧单图渲染失败**

Run:

```powershell
npm run test:run -- components/site/RoutePageBackground.test.tsx components/site/SiteChrome.test.tsx test/route-visual-style.test.ts
```

Expected: FAIL，旧组件只渲染一项且背景仍位于刘海之后。

- [ ] **Step 3: 实现全页多图背景组件**

核心接口：

```tsx
export default function RoutePageBackground({
  background,
  activeIndex = 0,
}: {
  background: PageVisualBackground;
  activeIndex?: number;
})
```

对 `background.images` 渲染同尺寸的固定全页层，使用 `activeIndex % images.length` 选择激活项。所有层共享同一 `background-position`、蒙版和滤镜，因此不存在刘海/正文裁切边界。

- [ ] **Step 4: 在 SiteChrome 中提升 Hub 当前索引**

`SiteChrome` 增加 `visualOverride?: RouteVisualConfig`，背景配置使用 `visualOverride ?? getRouteVisualConfig(pathname)`。状态初始化为 0，路由改变时复位；仅当 `routeVisual.id === "hub"` 时把 `SiteHeader` 的 `onQuoteChange` 连接到背景索引。

组件顺序调整为：

```tsx
<Navbar />
<div className="h-[52px]" />
{routeVisual && <RoutePageBackground background={routeVisual.page} activeIndex={activeBackgroundIndex} />}
{showHeader && <SiteHeader onQuoteChange={routeVisual?.id === "hub" ? setActiveBackgroundIndex : undefined} />}
<main>...</main>
```

- [ ] **Step 5: 修改 CSS 为连续背景和透明刘海**

`.route-page-background` 保持 `position: fixed; inset: 52px 0 0;`。每个图片层绝对铺满并通过 `opacity` 交叉淡入；全页蒙版只渲染一次。`.site-header-bg` 不再包含 `--site-header-image`，路由视觉页面的刘海背景保持透明，由 `.site-header-overlay` 提供局部暗色渐变。降低运动模式下禁用 opacity transition。

- [ ] **Step 6: 运行外壳和样式契约测试**

Run:

```powershell
npm run test:run -- components/site/RoutePageBackground.test.tsx components/site/SiteChrome.test.tsx components/site/SiteHeader.test.tsx test/route-visual-style.test.ts
```

Expected: PASS；背景先于刘海和正文，刘海没有图片地址，Hub 激活索引可切换。

- [ ] **Step 7: 提交连续背景核心**

```powershell
git add -- apps/web/components/site/RoutePageBackground.tsx apps/web/components/site/RoutePageBackground.test.tsx apps/web/components/site/SiteChrome.tsx apps/web/components/site/SiteChrome.test.tsx apps/web/app/styles/route-visuals.css apps/web/app/styles/navbar.css apps/web/test/route-visual-style.test.ts
git diff --cached --name-only --diff-filter=A | git check-ignore -v --no-index --stdin
git diff --cached --check
git commit -m "feat: 建立连续全页背景结构"
```

### Task 5: 接入 Landing、404 与 Admin 特殊页面

**Files:**
- Modify: `apps/web/app/(site)/page.tsx`
- Create: `apps/web/app/(site)/page.test.tsx`
- Modify: `apps/web/app/styles/landing.css`
- Modify: `apps/web/app/not-found.tsx`
- Modify: `apps/web/app/(site)/not-found.tsx`
- Modify: `apps/web/app/styles/not-found.css`
- Modify: `apps/web/app/(site)/layout.test.tsx`
- Modify: `apps/web/components/site/SiteChrome.test.tsx`
- Modify: `apps/web/test/route-visual-style.test.ts`

- [ ] **Step 1: 写失败测试，锁定 Landing 和 404 单背景**

Landing 测试断言元数据与 CSS 都引用 `/images/landing/home.webp`。404 测试断言外层向 `SiteChrome` 传入 `NOT_FOUND_VISUAL`，内部不再渲染 `.not-found-background`。

- [ ] **Step 2: 运行特殊页面测试并确认失败**

Run:

```powershell
npm run test:run -- "app/(site)/layout.test.tsx" components/site/SiteChrome.test.tsx
```

Expected: FAIL，Landing 与 404 仍使用旧的局部背景结构。

- [ ] **Step 3: 替换 Landing 正式资源**

把 `landingImage` 和 `.landing-background__image` 均改为 `/images/landing/home.webp`，保持现有 Landing 标题、打字机和按钮结构不变。

- [ ] **Step 4: 将 404 显式视觉覆盖交给 SiteChrome**

`app/not-found.tsx` 使用：

```tsx
<SiteChrome visualOverride={NOT_FOUND_VISUAL}>
  <SiteNotFound />
</SiteChrome>
```

删除 `SiteNotFound` 内部的 `.not-found-background` 节点和对应图片 CSS。404 页面自身保持透明，背景由外层从导航栏下方覆盖刘海和内容区域。

- [ ] **Step 5: 验证 Admin 已通过普通路由配置接入背景**

在 `SiteChrome.test.tsx` 断言 `/admin` 的视觉 id 为 `admin`、背景为 `admin.webp`，但 Admin 内容组件和业务结构不变。

- [ ] **Step 6: 运行特殊页面与完整路由测试**

Run:

```powershell
npm run test:run -- "app/(site)/layout.test.tsx" components/site/SiteChrome.test.tsx lib/route-visuals.test.ts
```

Expected: PASS。

- [ ] **Step 7: 提交特殊页面接入**

```powershell
git add -- "apps/web/app/(site)/page.tsx" "apps/web/app/(site)/page.test.tsx" apps/web/app/styles/landing.css apps/web/app/not-found.tsx "apps/web/app/(site)/not-found.tsx" apps/web/app/styles/not-found.css "apps/web/app/(site)/layout.test.tsx" apps/web/components/site/SiteChrome.test.tsx apps/web/test/route-visual-style.test.ts
git diff --cached --name-only --diff-filter=A | git check-ignore -v --no-index --stdin
git diff --cached --check
git commit -m "feat: 接入特殊页面全页背景"
```

### Task 6: 完成真实浏览器视觉验收与焦点微调

**Files:**
- Modify: `apps/web/lib/route-visuals.ts`
- Modify: `apps/web/app/styles/route-visuals.css`
- Modify: `apps/web/app/styles/navbar.css`
- Temporary only: `.codex-tmp/visual-background-qa.py`

- [ ] **Step 1: 确认临时 QA 路径受忽略并启动本地应用**

Run:

```powershell
git check-ignore -v .codex-tmp/visual-background-qa.py
python C:\Users\hhh20\.agents\skills\webapp-testing\scripts\with_server.py --help
```

Expected: 临时脚本受忽略；服务管理脚本显示帮助。

- [ ] **Step 2: 使用 Playwright 截取桌面和移动页面**

浏览器脚本使用 2048×1150 与 390×844 两组 viewport，至少截图 Hub 三帧、Search、Write、Login、About、User、Settings、Archive、Tag、Post、Landing、404 和 Admin。脚本读取计算样式并断言每个普通页面只有一个 `.route-page-background`，`SiteHeader` 不含背景图片。

- [ ] **Step 3: 验证 Hub 同步和减少动画模式**

使用假定时或等待真实打字机周期，记录三次文案索引变化，断言对应背景依次为 `hub-01.webp`、`hub-02.webp`、`hub-03.webp`。在 reduced-motion context 中确认图片仍随索引变化但没有 opacity transition。

- [ ] **Step 4: 逐页调整桌面与移动焦点位置**

只修改 `positionDesktop` 和 `positionMobile`，确保人物头部、脸部及主要叙事物体不被首屏严重裁切。不得修改用户指定图片、字体、卡片结构或页面内容。

- [ ] **Step 5: 删除 QA 临时脚本并提交焦点参数**

```powershell
git add -- apps/web/lib/route-visuals.ts apps/web/app/styles/route-visuals.css apps/web/app/styles/navbar.css
git diff --cached --name-only --diff-filter=A | git check-ignore -v --no-index --stdin
git diff --cached --check
git commit -m "style: 调整页面背景焦点与蒙版"
```

### Task 7: 完整验证、范围审计与发布准备

**Files:**
- Verify only: all task files

- [ ] **Step 1: 运行完整前端测试**

Run: `npm run test:run`

Expected: 26 个或更多测试文件全部通过，0 failures。

- [ ] **Step 2: 运行生产构建**

Run: `npm run build`

Expected: Next.js production build exit 0，TypeScript、静态页面和动态路由生成成功。

- [ ] **Step 3: 审计差异和临时文件**

Run:

```powershell
git diff --check
git status --short
git diff --stat origin/main...HEAD
git grep -n "hub-hero.webp\|hub-content.webp\|auth-arrival.webp\|landing/default.jpg" -- apps/web
```

Expected: 无空白错误；没有临时文件；旧站点背景无引用；`landing/default.jpg` 只允许由 Chtholly 房间继续引用。

- [ ] **Step 4: 执行提交前忽略审计**

Run:

```powershell
git diff --cached --name-only --diff-filter=A | git check-ignore -v --no-index --stdin
```

Expected: 无输出；任何输出都阻止提交。

- [ ] **Step 5: Fetch 并执行发布前忽略审计**

Run:

```powershell
git fetch origin
git diff --name-only --diff-filter=A origin/main...HEAD | git check-ignore -v --no-index --stdin
git log --left-right --cherry-pick origin/main...HEAD
git diff origin/main...HEAD
```

Expected: ignore 审计无输出；提交和差异仅包含本设计范围。

- [ ] **Step 6: 根据用户既定流程推送并创建 PR**

Push 当前 `codex/feat-continuous-page-backgrounds` 分支，创建目标为 `main` 的非草稿 PR；PR 中列出路由映射、Hub 同步、特殊页面处理、测试、构建和视觉截图验证。PR 合并前保留 worktree。
