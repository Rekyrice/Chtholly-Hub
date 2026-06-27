import Sidebar from "@/components/site/Sidebar";
import PostCard from "@/components/site/PostCard";
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
              className="flex-1 px-3 py-2 text-sm border outline-none focus:border-[#009688]"
              style={{ borderColor: "#e0e0e0", color: "#424242" }}
            />
            <button
              type="submit"
              className="px-4 py-2 text-sm text-white"
              style={{ backgroundColor: "#009688" }}
            >
              搜索
            </button>
          </form>
        </div>

        {keyword ? (
          <>
            {degraded && (
              <div
                className="mb-4 px-4 py-3 text-sm rounded border"
                style={{ borderColor: "#ffe0b2", backgroundColor: "#fff3e0", color: "#e65100" }}
              >
                搜索服务暂时不可用，请稍后再试。
              </div>
            )}
            <p className="mb-4 text-sm" style={{ color: "#757575" }}>
              「{keyword}」共 {items.length} 条结果
            </p>
            {items.length > 0 ? (
              items.map((post) => (
                <PostCard key={post.id} post={post} highlightDescription />
              ))
            ) : (
              <div className="post-card p-10 text-center" style={{ color: "#9e9e9e" }}>
                未找到相关帖子
              </div>
            )}
          </>
        ) : (
          <div className="post-card p-10 text-center" style={{ color: "#9e9e9e" }}>
            输入关键词开始搜索
          </div>
        )}
      </div>
      <Sidebar />
    </div>
  );
}
