import Link from "next/link";
import Sidebar from "@/components/site/Sidebar";
import { EmptyState } from "@/components/ui/EmptyState";
import { postService } from "@/lib/services/postService";
import { siteConfig } from "@/lib/site.config";
import { archiveGroupKey, formatArchiveMonth } from "@/lib/utils";

export const revalidate = 60;

type ArchiveEntry = {
  slug: string;
  title: string;
  publishTime: string;
};

async function loadArchiveEntries(): Promise<ArchiveEntry[]> {
  const feed = await postService.feed(1, 100, siteConfig.ownerUserId);
  const details = await Promise.all(
    feed.items.map((item) =>
      postService.detailBySlug(item.slug).catch(() => null),
    ),
  );

  return feed.items
    .map((item, i) => ({
      slug: item.slug,
      title: item.title,
      publishTime: details[i]?.publishTime ?? new Date().toISOString(),
    }))
    .sort(
      (a, b) =>
        new Date(b.publishTime).getTime() - new Date(a.publishTime).getTime(),
    );
}

export default async function ArchivePage() {
  let entries: ArchiveEntry[] = [];
  try {
    entries = await loadArchiveEntries();
  } catch {
    entries = [];
  }

  const groups = new Map<string, ArchiveEntry[]>();
  for (const entry of entries) {
    const key = archiveGroupKey(entry.publishTime);
    const list = groups.get(key) ?? [];
    list.push(entry);
    groups.set(key, list);
  }

  const sortedKeys = [...groups.keys()].sort((a, b) => b.localeCompare(a));

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[1fr_280px] gap-8 lg:items-start">
      <div>
        <div className="post-card mb-6 p-5">
          <h1 className="entry-title mb-0">Archive</h1>
          <p className="text-center text-text-secondary mt-3">
            共 {entries.length} 篇文章
          </p>
        </div>

        {sortedKeys.length > 0 ? (
          sortedKeys.map((key) => {
            const group = groups.get(key)!;
            const monthLabel = formatArchiveMonth(group[0].publishTime);
            return (
              <div key={key} className="post-card mb-6">
                <div className="entry-header pb-0">
                  <h2 className="entry-title text-[22px] mb-0">{monthLabel}</h2>
                </div>
                <ul className="list-none px-[72px] pb-9 m-0 max-md:px-6">
                  {group.map((entry) => (
                    <li
                      key={entry.slug}
                      className="border-b border-border py-3 last:border-b-0"
                    >
                      <Link
                        href={`/post/${entry.slug}`}
                        className="text-text text-base no-underline hover:text-sky transition-colors duration-150"
                      >
                        {entry.title}
                      </Link>
                    </li>
                  ))}
                </ul>
              </div>
            );
          })
        ) : (
          <EmptyState className="post-card" title="暂无归档内容" />
        )}
      </div>
      <Sidebar />
    </div>
  );
}
