/** Phase A 站点静态配置（后续可改为 CMS / API 驱动） */
export const siteConfig = {
  name: "Chtholly Hub",
  description: "Rekyrice 的个人动漫博客",
  author: {
    name: "Rekyrice",
    alias: "伊米花",
    bio: "动漫 · 追番 · 随笔",
    avatar: "/avatar-default.png",
  },
  theme: {
    primary: "#009688",
    bodyBg: "#eceff1",
  },
  /** Rekyrice 用户 ID，Feed 只展示该作者的公开帖子 */
  ownerUserId: Number(process.env.NEXT_PUBLIC_OWNER_USER_ID ?? "1"),
  nav: [
    { href: "/", label: "Home" },
    { href: "/archive", label: "Archive" },
    { href: "/about", label: "About" },
  ],
} as const;

export type SiteConfig = typeof siteConfig;
