import PostCard from "@/components/site/PostCard";
import { AnimateIn } from "@/components/ui/AnimateIn";
import { EmptyState } from "@/components/ui/EmptyState";
import { postService } from "@/lib/services/postService";
import { siteConfig } from "@/lib/site.config";
import type { FeedItem } from "@/lib/types/post";

type HomeFeedProps = {
  items?: FeedItem[];
  status?: "ok" | "degraded";
};

export default async function HomeFeed({ items: providedItems, status }: HomeFeedProps = {}) {
  let items: FeedItem[] = providedItems ?? [];

  if (providedItems == null) {
    try {
      const feed = await postService.feed(1, 20, siteConfig.ownerUserId);
      items = feed.items;
    } catch {
      items = [];
    }
  }

  if (status === "degraded") {
    return (
      <EmptyState
        className="post-card"
        title="文章暂时没有加载出来"
        description="搜索服务有点慢，稍后再回来看看。"
      />
    );
  }

  if (items.length === 0) {
    return (
      <EmptyState
        className="post-card"
        title="暂无文章"
        description="请确认后端已启动且种子数据已导入"
      />
    );
  }

  return items.map((post, index) => (
    <AnimateIn key={post.id} delay={index * 100}>
      <PostCard post={post} />
    </AnimateIn>
  ));
}
