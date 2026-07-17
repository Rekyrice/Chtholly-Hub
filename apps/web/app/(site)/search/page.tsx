import Sidebar from "@/components/site/Sidebar";
import { ChthollyIllustration } from "@/components/site/ChthollyIllustration";
import PostCard from "@/components/site/PostCard";
import { EmptyState } from "@/components/ui/EmptyState";
import { searchService } from "@/lib/services/searchService";
import type { ChthollyIllustrationProps } from "@/components/site/ChthollyIllustration";

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
  const currentTimePeriod = getCurrentTimePeriod();

  let items: Awaited<ReturnType<typeof searchService.search>>["items"] = [];
  let degraded = false;
  if (keyword) {
    try {
      const result = await searchService.search({ q: keyword, size: 20 });
      items = result.items;
      degraded = result.degraded === true;
    } catch {
      items = [];
      degraded = true;
    }
  }

  return (
    <div className="search-page-layout">
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
              <div className="search-empty post-card">
                <ChthollyIllustration size="md" state="curious" />
                <p>没找到呢……换个关键词试试？</p>
                <p className="text-text-secondary">珂朵莉歪着头看着你</p>
              </div>
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
      <div className="search-sidebar">
        <div className="widget sidebar-observation-widget search-sidebar__hint">
          <div className="sidebar-observation-widget__head">
            <ChthollyIllustration
              size="xs"
              mood={0}
              timeOfDay={currentTimePeriod}
              pageContext="/search"
            />
            <div>
              <span>Search</span>
              <h3>珂朵莉</h3>
            </div>
          </div>
          <p>我会在旁边看着结果。要是没找到，我们就换个词再试试。</p>
        </div>
        <Sidebar />
      </div>
    </div>
  );
}

function getCurrentTimePeriod(): NonNullable<ChthollyIllustrationProps["timeOfDay"]> {
  const hour = new Date().getHours();
  if (hour >= 6 && hour < 12) return "morning";
  if (hour >= 12 && hour < 18) return "afternoon";
  if (hour >= 18 && hour < 21) return "evening";
  if (hour >= 21 || hour < 1) return "night";
  return "late-night";
}
