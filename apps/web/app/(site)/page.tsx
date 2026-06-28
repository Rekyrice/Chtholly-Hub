import PostCard from "@/components/site/PostCard";
import Sidebar from "@/components/site/Sidebar";
import { EmptyState } from "@/components/ui/EmptyState";
import { postService } from "@/lib/services/postService";
import { siteConfig } from "@/lib/site.config";

export const revalidate = 60;

export default async function HomePage() {
  let items: Awaited<ReturnType<typeof postService.feed>>["items"] = [];

  try {
    const feed = await postService.feed(1, 20, siteConfig.ownerUserId);
    items = feed.items;
  } catch {
    items = [];
  }

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[1fr_280px] gap-8 lg:items-start">
      <div>
        {items.length > 0 ? (
          items.map((post) => <PostCard key={post.id} post={post} />)
        ) : (
          <EmptyState
            className="post-card"
            title="暂无文章"
            description="请确认后端已启动且种子数据已导入"
          />
        )}
      </div>
      <Sidebar />
    </div>
  );
}
