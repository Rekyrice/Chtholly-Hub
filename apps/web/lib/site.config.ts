/** Phase A 站点静态配置（后续可改为 CMS / API 驱动） */
const ownerHandle = process.env.NEXT_PUBLIC_OWNER_HANDLE ?? "rekyrice";

export const siteConfig = {
  name: "Chtholly Hub",
  description: "Rekyrice 的个人动漫博客",
  /** Hero 打字机轮播副标题 */
  heroQuotes: [
    "Rekyrice 的个人动漫博客",
    "私は幸せになりたい —— コトリ",
    "如果幸福有颜色，那一定是被终末之红染尽的蓝色",
  ],
  author: {
    name: "Rekyrice",
    /** 中文名「依米花」，仅用于 About 等解释性页面 */
    zhName: "依米花",
    bio: "动漫 · 追番 · 随笔",
    avatar: "/avatar-default.png",
  },
  theme: {
    primary: "#4ab0d9",
    secondary: "#8B5CF6",
    accent: "#E87461",
    bodyBg: "#F0F7FF",
  },
  /** Rekyrice 用户 ID，Feed 只展示该作者的公开帖子 */
  ownerUserId: Number(process.env.NEXT_PUBLIC_OWNER_USER_ID ?? "1"),
  /** 站长 handle，个人主页 /user/[handle] */
  ownerHandle,
  nav: [
    { href: "/", label: "Home" },
    { href: "/archive", label: "Archive" },
    { href: "/agent", label: "Agent" },
    { href: `/user/${ownerHandle}`, label: "Profile" },
    { href: "/about", label: "About" },
  ],
} as const;

export type SiteConfig = typeof siteConfig;
