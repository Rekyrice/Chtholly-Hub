import Sidebar from "@/components/site/Sidebar";
import { siteConfig } from "@/lib/site.config";

export default function AboutPage() {
  const { author, name, description } = siteConfig;

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[1fr_280px] gap-8 lg:items-start">
      <div className="post-card">
        <div className="entry-header">
          <h1 className="entry-title entry-title-single">About</h1>
          <div className="entry-meta">{author.name}</div>
        </div>
        <div className="prose-anime pb-12">
          <p>
            欢迎来到 <strong>{name}</strong> — {description}。
          </p>
          <p>{author.bio}</p>
          <p>
            Rekyrice 的中文名是「{author.zhName}」。这是流行于励志散文里的一种<strong>虚构</strong>花名（现实植物界并不存在），传说生长在非洲荒漠：须数年向下扎根、积蓄养分，再在极短的花期内开出四色小花，随后随母株一同枯萎。常见解读是<strong>生命一次、美丽一次</strong>——漫长沉默里的顽强，与瞬间绚烂并存；也常被赋予圣洁、和平，以及「转瞬即逝的爱 / 奇迹」一类花语。
          </p>
          <p>
            本站基于 Next.js + Spring Boot Monorepo 构建，Phase A 为只读博客：
            首页 Feed、Markdown 详情、归档与标签浏览。
          </p>
          <p>
            后续将逐步开放登录发帖、社区互动与 AI 番剧助手能力。
          </p>
        </div>
      </div>
      <Sidebar />
    </div>
  );
}
