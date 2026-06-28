import Sidebar from "@/components/site/Sidebar";
import PostCard from "@/components/site/PostCard";
import { EmptyState } from "@/components/ui/EmptyState";
import { searchService } from "@/lib/services/searchService";

interface Props {
  searchParams: Promise<{ q?: string }>;
}

export const revalidate = 60;

export async function generateMetadata({ searchParams }: Props) {
  const { q } = await searchParams;
  const keyword = q?.trim();
  return { title: keyword ? `搜索：${keyword}` : "搜索" };
}

export default async function SearchPage({ searchParams }: Props) {
  const { q } = await searchParams;
  const keyword = q?.trim() ?? "";

  let items: Awaited<ReturnType<typeof searchService.search>>["items"] = [];
  let degraded = false;
  if (keyword) {
    try {
      const result = await searchService.search(keyword, 20);
      items = result.items;
      degraded = result.degraded === true;
    } catch {
      items = [];
      degraded = true;
    }
  }

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[1fr_280px] gap-8 lg:items-start">
      <div>
        <div className="post-card mb-6 p-5">
          <form action="/search" method="get" className="flex gap-2">
            <input
              type="search"
              name="q"
              defaultValue={keyword}
              placeholder="搜索帖子标题或正文…"
              className="field-input flex-1 text-sm"
            />
            <button
              type="submit"
              className="px-4 py-2 text-sm bg-sky text-on-primary rounded-lg transition-colors duration-150 hover:bg-sky-deep"
            >
              搜索
            </button>
          </form>
        </div>

        {keyword ? (
          <>
            {degraded && (
              <div className="mb-4 px-4 py-3 text-sm rounded-lg border alert-warn">
                搜索服务暂时不可用，请稍后再试。
              </div>
            )}
            <p className="mb-4 text-sm text-text-secondary">
              「{keyword}」共 {items.length} 条结果
            </p>
            {items.length > 0 ? (
              items.map((post) => (
                <PostCard key={post.id} post={post} highlightDescription />
              ))
            ) : (
              <EmptyState
                className="post-card"
                title="未找到相关帖子"
                description="换个关键词试试"
              />
            )}
          </>
        ) : (
          <EmptyState
            className="post-card"
            title="输入关键词开始搜索"
            description="支持搜索帖子标题与正文"
          />
        )}
      </div>
      <Sidebar />
    </div>
  );
}
