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
        <div className="post-card-image">
          <Link href={`/post/${post.slug}`} className="block">
            <div className="relative w-full aspect-[1038/576] overflow-hidden rounded-lg">
              <Image
                src={post.coverImage}
                alt={post.title}
                fill
                className="object-cover"
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
            className="inline-block mb-2.5 no-underline"
          >
            <span className="tag-badge">{post.tags[0]}</span>
          </Link>
        )}

        <h2 className="entry-title">
          <Link href={`/post/${post.slug}`}>{post.title}</Link>
        </h2>

        <div className="entry-meta">
          <span className="mr-4 inline-flex items-center gap-1">
            <User size={13} className="inline" />
            {post.authorNickname || authorName}
          </span>
          {post.likeCount != null && post.likeCount > 0 && (
            <span className="inline-flex items-center gap-1">
              <Heart size={13} className="inline" />
              {post.likeCount}
            </span>
          )}
        </div>
      </div>

      {post.description &&
        (highlightDescription ? (
          <div
            className="entry-summary search-snippet"
            dangerouslySetInnerHTML={{ __html: post.description }}
          />
        ) : (
          <div className="entry-summary">{post.description}</div>
        ))}
    </article>
  );
}
