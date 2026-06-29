# 静态图片资源

本目录存放 **Next.js 可直接通过 URL 访问** 的图片（位于 `apps/web/public/images/`）。

访问路径规则：文件 `public/images/agent/banner.webp` → 浏览器路径 `/images/agent/banner.webp`

## 子目录

| 目录 | 用途 | 示例 |
|------|------|------|
| `brand/` | Logo、站点标识 | `logo.svg`、`logo-dark.png` |
| `site/` | 首页、About 等页面装饰图 | `hero-bg.webp` |
| `agent/` | Agent 工作台 UI 图（非 Live2D） | 背景纹理、空状态插图 |
| `avatars/` | 默认头像、占位图 | `default.png` |
| `og/` | Open Graph / 分享预览图 | `default-og.png` |
| `_incoming/` | **临时投放区**：先把图片放这里，再在对话里说明用途，由开发整理到对应子目录 | 任意文件名 |

## `_incoming/` 使用方式

1. 把图片复制到 `public/images/_incoming/`
2. 在对话里说明：文件名 → 用在哪个页面/组件（可附期望尺寸或效果）
3. 确认用途后，会移动到 `brand/`、`site/`、`agent/` 等正式目录，并改代码引用

**注意：** `_incoming/` 仅作中转，不要长期把正式资源只留在这里；整理完成后该目录应保持为空或只有 `.gitkeep`。

## 不放在这里的资源

- **Live2D 模型与贴图**：继续放在 `public/live2d/`
- **文章正文图片、用户上传内容**：走 OSS / API，不要提交进仓库
- **favicon**：放在 `app/favicon.ico`（Next.js App Router 约定）

## 代码中引用

```tsx
import Image from "next/image";

<Image src="/images/site/hero.webp" alt="..." width={1200} height={630} />
```

或配置常量：

```ts
export const SITE_OG_IMAGE = "/images/og/default.png";
```
