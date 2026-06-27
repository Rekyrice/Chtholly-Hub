import Sidebar from "@/components/site/Sidebar";
import PostCard from "@/components/site/PostCard";
import { postService } from "@/lib/services/postService";
import { tagService } from "@/lib/services/tagService";
import { siteConfig } from "@/lib/site.config";

interface Props {
  params: Promise<{ name: string }>;
}

export const revalidate = 60;

export async function generateMetadata({ params }: Props) {
  const { name } = await params;
  const tagName = decodeURIComponent(name);
  return { title: `标签：${tagName}` };
}

export default async function TagPage({ params }: Props) {
  const { name } = await params;
  const tagName = decodeURIComponent(name);

  let items: Awaited<ReturnType<typeof postService.feed>>["items"] = [];
  let usageCount: number | null = null;

  try {
    const feed = await postService.feed(1, 50, siteConfig.ownerUserId, tagName);
    items = feed.items;
  } catch {
    items = [];
  }

  try {
    const tags = await tagService.list(200);
    const matched = tags.find((t) => t.name === tagName);
    usageCount = matched?.usageCount ?? items.length;
  } catch {
    usageCount = items.length;
  }

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[1fr_280px] gap-8 lg:items-start">
      <div>
        <div className="post-card mb-6 p-5 flex items-center gap-3">
          <span
            style={{
              padding: "4px 12px",
              fontSize: 13,
              color: "#fff",
              backgroundColor: siteConfig.theme.primary,
            }}
          >
            # {tagName}
          </span>
          <span style={{ fontSize: 14, color: "#757575" }}>
            {usageCount ?? items.length} 篇文章
          </span>
        </div>

        {items.length > 0 ? (
          items.map((post) => <PostCard key={post.id} post={post} />)
        ) : (
          <div className="post-card p-10 text-center" style={{ color: "#9e9e9e" }}>
            该标签暂无文章
          </div>
        )}
      </div>
      <Sidebar />
    </div>
  );
}
