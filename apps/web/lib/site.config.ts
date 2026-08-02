/** Phase A 站点静态配置（后续可改为 CMS / API 驱动） */
const ownerHandle = process.env.NEXT_PUBLIC_OWNER_HANDLE ?? "Rekyrice";

export const siteConfig = {
  name: "Chtholly Hub",
  description: "Rekyrice 的个人动漫博客",
  /** Hero 打字机轮播副标题 */
  heroQuotes: [
    "私のことは、忘れてくれると嬉しいかな。",
    "私……もう、とっくに幸せだったんだって。",
    "だから、きっと……今の私は……誰がなんと言おうと……世界一、幸せな女の子だ。",
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
    { href: "/hub", label: "Hub" },
    { href: "/chtholly", label: "Chtholly" },
    { href: "/search", label: "Search" },
    { href: "/write", label: "Write" },
  ],
} as const;

export type SiteConfig = typeof siteConfig;
