import Link from "next/link";
import Sidebar from "@/components/site/Sidebar";
import { postService } from "@/lib/services/postService";
import { siteConfig } from "@/lib/site.config";
import { archiveGroupKey, formatArchiveMonth } from "@/lib/utils";

export const revalidate = 60;

type ArchiveEntry = {
  slug: string;
  title: string;
  publishTime: string;
};

/** 拉取 Feed 并补充 publishTime（Feed 不含日期，需查详情） */
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
          <h1 className="entry-title" style={{ marginBottom: 0 }}>
            Archive
          </h1>
          <p style={{ textAlign: "center", color: "#727272", marginTop: 12 }}>
            共 {entries.length} 篇文章
          </p>
        </div>

        {sortedKeys.length > 0 ? (
          sortedKeys.map((key) => {
            const group = groups.get(key)!;
            const monthLabel = formatArchiveMonth(group[0].publishTime);
            return (
              <div key={key} className="post-card mb-6">
                <div className="entry-header" style={{ paddingBottom: 0 }}>
                  <h2
                    className="entry-title"
                    style={{ fontSize: 22, marginBottom: 0 }}
                  >
                    {monthLabel}
                  </h2>
                </div>
                <ul
                  style={{
                    listStyle: "none",
                    padding: "0 72px 36px",
                    margin: 0,
                  }}
                >
                  {group.map((entry) => (
                    <li
                      key={entry.slug}
                      style={{
                        borderBottom: "1px solid #f5f5f5",
                        padding: "12px 0",
                      }}
                    >
                      <Link
                        href={`/post/${entry.slug}`}
                        style={{
                          color: "#424242",
                          textDecoration: "none",
                          fontSize: 16,
                        }}
                        className="hover:text-[#009688]"
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
          <div className="post-card p-16 text-center" style={{ color: "#9e9e9e" }}>
            暂无归档内容
          </div>
        )}
      </div>
      <Sidebar />
    </div>
  );
}
