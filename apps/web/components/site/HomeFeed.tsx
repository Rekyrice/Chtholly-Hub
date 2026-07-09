import Link from "next/link";
import PostCard from "@/components/site/PostCard";
import { AnimateIn } from "@/components/ui/AnimateIn";
import { EmptyState } from "@/components/ui/EmptyState";
import { postService } from "@/lib/services/postService";
import { siteConfig } from "@/lib/site.config";
import type { FeedItem } from "@/lib/types/post";

type HomeFeedProps = {
  items?: FeedItem[];
  status?: "ok" | "degraded";
  totalItems?: number;
  currentPage?: number;
  pageSize?: number;
};

export default async function HomeFeed({
  items: providedItems,
  status,
  totalItems,
  currentPage = 1,
  pageSize = 8,
}: HomeFeedProps = {}) {
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

  const total = Math.max(totalItems ?? items.length, items.length);
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(Math.max(currentPage, 1), totalPages);
  const start = (safePage - 1) * pageSize;
  const end = Math.min(start + items.length, total);

  return (
    <>
      <div className="hub-feed-list">
        {items.map((post, index) => (
          <AnimateIn key={post.id} delay={index * 100}>
            <PostCard post={post} />
          </AnimateIn>
        ))}
      </div>

      {total > pageSize && (
        <nav className="hub-feed-pagination" aria-label="仓库动态分页">
          <div>
            <span>第 {safePage} / {totalPages} 页</span>
            <small>
              显示 {start + 1}-{end} / {total} 篇
            </small>
          </div>
          <div className="hub-feed-pagination__actions">
            {safePage > 1 ? (
              <Link href={`/hub?page=${safePage - 1}`}>上一页</Link>
            ) : (
              <span aria-disabled="true">上一页</span>
            )}
            {safePage < totalPages ? (
              <Link href={`/hub?page=${safePage + 1}`}>下一页</Link>
            ) : (
              <span aria-disabled="true">下一页</span>
            )}
          </div>
        </nav>
      )}
    </>
  );
}
