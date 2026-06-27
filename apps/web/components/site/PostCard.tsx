import Image from "next/image";
import Link from "next/link";
import { Heart, User } from "lucide-react";
import type { FeedItem } from "@/lib/types/post";
import { siteConfig } from "@/lib/site.config";

interface PostCardProps {
  post: FeedItem;
  authorName?: string;
  /** 搜索摘要：description 含 ES 高亮 em 标签时用 HTML 渲染 */
  highlightDescription?: boolean;
}

export default function PostCard({
  post,
  authorName = siteConfig.author.name,
  highlightDescription = false,
}: PostCardProps) {
  return (
    <article className="post-card">
      {post.coverImage && (
        <div style={{ padding: "10px 10px 0" }}>
          <Link href={`/post/${post.slug}`} className="group block">
            <div
              style={{
                position: "relative",
                width: "100%",
                aspectRatio: "1038/576",
                overflow: "hidden",
                borderRadius: "8px",
              }}
            >
              <Image
                src={post.coverImage}
                alt={post.title}
                fill
                className="object-cover transition-transform duration-300 group-hover:scale-[1.03]"
                sizes="(max-width: 768px) 100vw, 848px"
              />
            </div>
          </Link>
        </div>
      )}

      <div className="entry-header">
        {post.tags.length > 0 && (
          <Link
            href={`/tag/${encodeURIComponent(post.tags[0])}`}
            style={{
              display: "inline-block",
              fontSize: 12,
              color: "#ffffff",
              backgroundColor: siteConfig.theme.primary,
              padding: "2px 8px",
              textTransform: "uppercase",
              letterSpacing: 1,
              marginBottom: 10,
              textDecoration: "none",
            }}
          >
            {post.tags[0]}
          </Link>
        )}

        <h2 className="entry-title">
          <Link href={`/post/${post.slug}`}>{post.title}</Link>
        </h2>

        <div className="entry-meta">
          <span style={{ marginRight: 16 }}>
            <User
              size={13}
              style={{ display: "inline", marginRight: 4, verticalAlign: "middle" }}
            />
            {post.authorNickname || authorName}
          </span>
          {post.likeCount != null && post.likeCount > 0 && (
            <span>
              <Heart
                size={13}
                style={{
                  display: "inline",
                  marginRight: 3,
                  verticalAlign: "middle",
                }}
              />
              {post.likeCount}
            </span>
          )}
        </div>
      </div>

      {post.description && (
        highlightDescription ? (
          <div
            className="entry-summary search-snippet"
            dangerouslySetInnerHTML={{ __html: post.description }}
          />
        ) : (
          <div className="entry-summary">{post.description}</div>
        )
      )}
    </article>
  );
}
