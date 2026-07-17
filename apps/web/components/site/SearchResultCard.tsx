import Image from "next/image";
import Link from "next/link";
import type { ReactNode } from "react";
import type { FeedItem } from "@/lib/types/post";
import { formatDate } from "@/lib/utils";

type SearchResultCardProps = {
  post: FeedItem;
  query: string;
};

function safeText(value: unknown, fallback: string) {
  return typeof value === "string" && value.trim() ? value.trim() : fallback;
}

function safeTags(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value
    .filter((tag): tag is string => typeof tag === "string")
    .map((tag) => tag.trim())
    .filter(Boolean);
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function highlightedText(value: string, query: string): ReactNode {
  const terms = [...new Set(query.trim().split(/\s+/u))]
    .filter(Boolean)
    .slice(0, 8)
    .map((term) => term.slice(0, 64));
  if (terms.length === 0) return value;

  const expression = new RegExp(`(${terms.map(escapeRegExp).join("|")})`, "giu");
  const normalizedTerms = new Set(terms.map((term) => term.toLocaleLowerCase()));

  return value.split(expression).map((part, index) =>
    normalizedTerms.has(part.toLocaleLowerCase()) ? (
      <mark key={`${part}-${index}`}>{part}</mark>
    ) : (
      part
    ),
  );
}

function readableDate(value: unknown) {
  if (typeof value !== "string" || !value.trim() || Number.isNaN(Date.parse(value))) {
    return null;
  }
  try {
    return formatDate(value);
  } catch {
    return null;
  }
}

export default function SearchResultCard({ post, query }: SearchResultCardProps) {
  const title = safeText(post.title, "未命名文章");
  const description = safeText(post.description, "这篇文章暂时没有摘要。");
  const author = safeText(post.authorNickname, "匿名作者");
  const slug = safeText(post.slug, "");
  const tags = safeTags(post.tags);
  const coverImage = safeText(post.coverImage, "");
  const publishedAt = readableDate(post.publishTime);
  const postHref = `/post/${encodeURIComponent(slug)}`;

  return (
    <article className="search-result-card">
      <Link href={postHref} className="search-result-card__media" aria-label={`阅读：${title}`}>
        {coverImage ? (
          <Image
            src={coverImage}
            alt=""
            fill
            className="search-result-card__image"
            sizes="(max-width: 640px) 100vw, 240px"
          />
        ) : (
          <span className="search-result-card__fallback" aria-hidden="true">
            {tags[0] ?? "文章"}
          </span>
        )}
      </Link>

      <div className="search-result-card__body">
        <div className="search-result-card__meta">
          {publishedAt && typeof post.publishTime === "string" && (
            <time dateTime={post.publishTime}>{publishedAt}</time>
          )}
          <span>{author}</span>
        </div>

        <h2 className="search-result-card__title">
          <Link href={postHref}>{highlightedText(title, query)}</Link>
        </h2>
        <p className="search-result-card__description">
          {highlightedText(description, query)}
        </p>

        {tags.length > 0 && (
          <ul className="search-result-card__tags" aria-label="文章标签">
            {tags.slice(0, 3).map((tag) => (
              <li key={tag}>
                <Link
                  href={`/tag/${encodeURIComponent(tag)}`}
                  className="search-result-card__tag"
                >
                  {tag}
                </Link>
              </li>
            ))}
          </ul>
        )}
      </div>
    </article>
  );
}
