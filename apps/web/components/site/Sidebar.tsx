import Link from "next/link";
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
    <aside
      className="sidebar-scroll-hidden"
      style={{
        position: "sticky",
        top: 52,
        maxHeight: "calc(100vh - 52px)",
        overflowY: "auto",
      }}
    >
      <div className="widget" style={{ textAlign: "center" }}>
        <div
          style={{
            width: 160,
            height: 160,
            margin: "0 auto",
            borderRadius: "50%",
            overflow: "hidden",
            boxShadow: "0 2px 14px rgba(0,0,0,0.08)",
            border: "2px solid rgba(255,255,255,0.75)",
            background: "#e0f2f1",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            color: siteConfig.theme.primary,
            fontSize: 48,
            fontWeight: 700,
          }}
        >
          仁
        </div>
        <div
          style={{ marginTop: 14, fontSize: 18, color: "#424242", fontWeight: 500 }}
        >
          {profileName}
        </div>
        <p style={{ marginTop: 8, fontSize: 14, color: "#727272" }}>
          {siteConfig.author.bio}
        </p>
        <div
          style={{
            marginTop: 12,
            display: "grid",
            gridTemplateColumns: "repeat(2, 1fr)",
            gap: 6,
          }}
        >
          <Link href="/" style={{ textDecoration: "none" }} className="hover:opacity-80">
            <div style={{ fontSize: 30, lineHeight: 1.1, color: "#424242" }}>
              {items.length}
            </div>
            <div style={{ fontSize: 14, color: "#727272" }}>文章</div>
          </Link>
          <Link href="/archive" style={{ textDecoration: "none" }} className="hover:opacity-80">
            <div style={{ fontSize: 30, lineHeight: 1.1, color: "#424242" }}>
              {tags.length}
            </div>
            <div style={{ fontSize: 14, color: "#727272" }}>标签</div>
          </Link>
        </div>
      </div>

      {items.length > 0 && (
        <div className="widget">
          <h3 className="widget-title">最新文章</h3>
          <ul style={{ listStyle: "none", padding: 0, margin: 0 }}>
            {items.slice(0, 5).map((post) => (
              <li
                key={post.id}
                style={{
                  borderBottom: "1px solid #f5f5f5",
                  padding: "8px 0",
                  fontSize: 14,
                }}
              >
                <Link
                  href={`/post/${post.slug}`}
                  style={{ color: "#424242", textDecoration: "none", display: "block" }}
                  className="hover:text-[#009688]"
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
          <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
            {tags.map((tag) => (
              <Link
                key={tag.id}
                href={`/tag/${encodeURIComponent(tag.name)}`}
                style={{
                  padding: "3px 10px",
                  fontSize: 12,
                  color: "#ffffff",
                  backgroundColor: siteConfig.theme.primary,
                  textDecoration: "none",
                  letterSpacing: 0.5,
                }}
                className="hover:opacity-80 transition-opacity"
                title={`${tag.usageCount} 篇`}
              >
                {tag.name}
              </Link>
            ))}
          </div>
        </div>
      )}
    </aside>
  );
}
