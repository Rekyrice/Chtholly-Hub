import Link from "next/link";
import { Badge } from "@/components/ui/Badge";
import { postService } from "@/lib/services/postService";
import { tagService } from "@/lib/services/tagService";
import { siteConfig } from "@/lib/site.config";
import type { FeedItem } from "@/lib/types/post";
import type { TagItem } from "@/lib/types/tag";

export default async function Sidebar() {
  let items: FeedItem[] = [];
  let tags: TagItem[] = [];

  try {
    const feed = await postService.feed(1, 50, siteConfig.ownerUserId);
    items = feed.items;
  } catch {
    items = [];
  }

  try {
    tags = await tagService.list(50);
  } catch {
    tags = [];
  }

  const profileName = siteConfig.author.name;

  return (
    <aside className="sidebar-scroll-hidden sticky top-[52px] max-h-[calc(100vh-52px)] overflow-y-auto">
      <div className="widget text-center">
        <Link
          href={`/user/${siteConfig.ownerHandle}`}
          className="no-underline text-inherit hover:opacity-90 transition-opacity duration-150"
        >
          <div className="w-40 h-40 mx-auto rounded-full overflow-hidden shadow-md border-2 border-surface avatar-ring flex items-center justify-center text-sky text-5xl font-bold">
            仁
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

      {items.length > 0 && (
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

      {tags.length > 0 && (
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
