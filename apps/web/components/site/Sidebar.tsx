import Link from "next/link";
import { Badge } from "@/components/ui/Badge";
import { postService } from "@/lib/services/postService";
import { tagService } from "@/lib/services/tagService";
import { siteConfig } from "@/lib/site.config";
import type { FeedItem } from "@/lib/types/post";
import type { AgentExperienceItem } from "@/lib/types/search";
import type { TagItem } from "@/lib/types/tag";

type SidebarProps = {
  items?: FeedItem[];
  tags?: TagItem[];
  recommendations?: FeedItem[];
  experiences?: AgentExperienceItem[];
  latestStatus?: "ok" | "degraded";
  tagsStatus?: "ok" | "degraded";
  recommendationsStatus?: "ok" | "degraded";
  experiencesStatus?: "ok" | "degraded";
};

export default async function Sidebar({
  items: providedItems,
  tags: providedTags,
  recommendations = [],
  experiences = [],
  latestStatus,
  tagsStatus,
  recommendationsStatus,
  experiencesStatus,
}: SidebarProps = {}) {
  let items: FeedItem[] = providedItems ?? [];
  let tags: TagItem[] = providedTags ?? [];

  if (providedItems == null) {
    try {
      const feed = await postService.feed(1, 50, siteConfig.ownerUserId);
      items = feed.items;
    } catch {
      items = [];
    }
  }

  if (providedTags == null) {
    try {
      tags = await tagService.list(50);
    } catch {
      tags = [];
    }
  }

  const profileName = siteConfig.author.name;

  return (
    <aside className="sidebar-scroll-hidden sticky top-[52px] max-h-[calc(100vh-52px)] overflow-y-auto">
      <div className="widget text-center">
        <Link
          href={`/user/${siteConfig.ownerHandle}`}
          className="no-underline text-inherit hover:opacity-90 transition-opacity duration-150"
        >
          <div className="w-40 h-40 mx-auto rounded-full overflow-hidden shadow-md border-2 border-surface flex items-center justify-center">
            <span className="navbar-brand-icon navbar-brand-icon--lg" aria-hidden="true">
              C
            </span>
          </div>
          <div className="mt-3.5 text-lg text-text font-medium">{profileName}</div>
        </Link>
        <p className="mt-2 text-sm text-text-secondary">{siteConfig.author.bio}</p>
        <div className="mt-3 grid grid-cols-2 gap-1.5">
          <Link href="/" className="no-underline hover:opacity-80 transition-opacity duration-150">
            <div className="text-3xl leading-tight text-text">{items.length}</div>
            <div className="text-sm text-text-secondary">文章</div>
          </Link>
          <Link href="/archive" className="no-underline hover:opacity-80 transition-opacity duration-150">
            <div className="text-3xl leading-tight text-text">{tags.length}</div>
            <div className="text-sm text-text-secondary">标签</div>
          </Link>
        </div>
      </div>

      {latestStatus === "degraded" && (
        <div className="widget">
          <h3 className="widget-title">最新文章</h3>
          <p className="text-sm text-text-secondary m-0">暂时无法获取，稍后再试试。</p>
        </div>
      )}

      {latestStatus !== "degraded" && items.length > 0 && (
        <div className="widget">
          <h3 className="widget-title">最新文章</h3>
          <ul className="list-none p-0 m-0">
            {items.slice(0, 5).map((post) => (
              <li
                key={post.id}
                className="border-b border-border py-2 text-sm last:border-b-0"
              >
                <Link
                  href={`/post/${post.slug}`}
                  className="text-text no-underline block hover:text-sky transition-colors duration-150"
                >
                  {post.title}
                </Link>
              </li>
            ))}
          </ul>
        </div>
      )}

      {recommendationsStatus === "degraded" && (
        <div className="widget">
          <h3 className="widget-title">推荐内容</h3>
          <p className="text-sm text-text-secondary m-0">推荐暂时走丢了，等一下就好。</p>
        </div>
      )}

      {recommendationsStatus !== "degraded" && recommendations.length > 0 && (
        <div className="widget">
          <h3 className="widget-title">推荐内容</h3>
          <ul className="list-none p-0 m-0">
            {recommendations.slice(0, 5).map((post) => (
              <li
                key={post.id}
                className="border-b border-border py-2 text-sm last:border-b-0"
              >
                <Link
                  href={`/post/${post.slug}`}
                  className="text-text no-underline block hover:text-sky transition-colors duration-150"
                >
                  {post.title}
                </Link>
              </li>
            ))}
          </ul>
        </div>
      )}

      {experiencesStatus === "degraded" && (
        <div className="widget">
          <h3 className="widget-title">珂朵莉最近在想</h3>
          <p className="text-sm text-text-secondary m-0">她现在有点安静，稍后再听听看。</p>
        </div>
      )}

      {experiencesStatus !== "degraded" && experiences.length > 0 && (
        <div className="widget">
          <h3 className="widget-title">珂朵莉最近在想</h3>
          <ul className="list-none p-0 m-0">
            {experiences.slice(0, 3).map((experience, index) => (
              <li
                key={`${experience.createdAt ?? "experience"}-${index}`}
                className="border-b border-border py-2 text-sm text-text-secondary last:border-b-0"
              >
                {experience.text}
              </li>
            ))}
          </ul>
        </div>
      )}

      {tagsStatus === "degraded" && (
        <div className="widget">
          <h3 className="widget-title">标签</h3>
          <p className="text-sm text-text-secondary m-0">标签热度暂时没有回来。</p>
        </div>
      )}

      {tagsStatus !== "degraded" && tags.length > 0 && (
        <div className="widget">
          <h3 className="widget-title">标签</h3>
          <div className="flex flex-wrap gap-1.5">
            {tags.map((tag) => (
              <Link
                key={tag.id}
                href={`/tag/${encodeURIComponent(tag.name)}`}
                className="no-underline hover:opacity-80 transition-opacity duration-150"
                title={`${tag.usageCount} 篇`}
              >
                <Badge className="bg-sky text-on-primary hover:bg-sky-deep">{tag.name}</Badge>
              </Link>
            ))}
          </div>
        </div>
      )}
    </aside>
  );
}
