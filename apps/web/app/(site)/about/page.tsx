import Sidebar from "@/components/site/Sidebar";
import { siteConfig } from "@/lib/site.config";

export default function AboutPage() {
  const { author, name, description } = siteConfig;

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[1fr_280px] gap-8 lg:items-start">
      <div className="post-card">
        <div className="entry-header">
          <h1 className="entry-title entry-title-single">About</h1>
          <div className="entry-meta">{author.name} · {author.alias}</div>
        </div>
        <div className="prose-anime pb-12">
          <p>
            欢迎来到 <strong>{name}</strong> — {description}。
          </p>
          <p>{author.bio}</p>
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
