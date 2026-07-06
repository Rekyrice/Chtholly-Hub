import Image from "next/image";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Clock, Tag } from "lucide-react";
import MarkdownContent from "@/components/site/MarkdownContent";
import CommentSection from "@/components/site/CommentSection";
import ArticleActions from "@/components/site/ArticleActions";
import {
  ChthollyIllustration,
  type ChthollyIllustrationProps,
  type IllustrationState,
} from "@/components/site/ChthollyIllustration";
import { Badge } from "@/components/ui/Badge";
import { postService } from "@/lib/services/postService";
import type { PostDetailResponse } from "@/lib/types/post";
import { formatDate } from "@/lib/utils";

interface Props {
  params: Promise<{ slug: string }>;
}

export const revalidate = 60;

type ArticleKind = "technical" | "reflection" | "default";

export async function generateMetadata({ params }: Props) {
  const { slug } = await params;
  try {
    const post = await postService.detailBySlug(slug);
    return {
      title: post.title,
      description: post.description,
    };
  } catch {
    return { title: "文章不存在" };
  }
}

export default async function PostPage({ params }: Props) {
  const { slug } = await params;

  let post: PostDetailResponse;
  try {
    post = await postService.detailBySlug(slug);
  } catch {
    notFound();
  }

  let markdown = "";
  try {
    const res = await fetch(post.contentUrl, { next: { revalidate: 300 } });
    if (!res.ok) {
      throw new Error(`无法加载正文：${res.status}`);
    }
    markdown = await res.text();
  } catch {
    markdown = "*正文暂时无法加载，请稍后再试。*";
  }

  const cover = post.images?.[0];
  const readingState = getArticleReadingState(post);
  const readingComment = getReadingComment(post);
  const timeOfDay = getCurrentTimeOfDay();
  const askHref = `/agent?context=${encodeURIComponent(`post:${post.slug}`)}`;

  return (
    <div className="article-detail-layout">
      <main className="article-main">
        <article className="post-card">
          {cover && (
            <div className="post-card-image">
              <div className="relative w-full aspect-[1038/576]">
                <Image
                  src={cover}
                  alt={post.title}
                  fill
                  className="object-cover"
                  priority
                  sizes="(max-width:1024px) 100vw, 780px"
                />
              </div>
            </div>
          )}

          <div className="entry-header">
            <h1 className="entry-title entry-title-single">{post.title}</h1>
            <div className="entry-meta">
              {post.publishTime && (
                <span className="mr-4 inline-flex items-center gap-1">
                  <Clock size={13} className="inline" />
                  {formatDate(post.publishTime)}
                </span>
              )}
              <span className="mr-4">{post.authorNickname}</span>
            </div>
            <ArticleActions
              postId={post.id}
              slug={post.slug}
              initialLiked={post.liked}
              initialFaved={post.faved}
              initialLikeCount={post.likeCount}
              initialFavCount={post.favoriteCount}
            />
          </div>

          <MarkdownContent content={markdown} />

          {post.tags.length > 0 && (
            <div className="px-[72px] pb-6 pt-5 border-t border-border max-md:px-6">
              <div className="flex flex-wrap items-center gap-2">
                <Tag size={14} className="text-text-secondary" />
                {post.tags.map((tag) => (
                  <Link
                    key={tag}
                    href={`/tag/${encodeURIComponent(tag)}`}
                    className="no-underline hover:opacity-80 transition-opacity duration-150"
                  >
                    <Badge className="bg-sky text-on-primary text-xs px-2.5 py-0.5">
                      {tag}
                    </Badge>
                  </Link>
                ))}
              </div>
            </div>
          )}

        </article>
        <div className="article-ask-chtholly">
          <ChthollyIllustration size="xs" state="curious" />
          <div className="article-ask-content">
            <p>对这篇文章有什么想法？</p>
            <Link href={askHref} className="article-ask-btn">
              问珂朵莉
            </Link>
          </div>
        </div>
        <CommentSection postId={post.id} />
      </main>

      <aside className="article-sidebar" aria-label="问珂朵莉关于这篇文章">
        <ChthollyIllustration
          size="sm"
          state={readingState}
          mood={0}
          timeOfDay={timeOfDay}
        />
        <p className="article-sidebar-text">{readingComment}</p>
        <Link href={askHref} className="article-sidebar-btn">
          问珂朵莉
        </Link>
      </aside>
    </div>
  );
}

function getArticleReadingState(post: PostDetailResponse): IllustrationState {
  const kind = getArticleKind(post);
  if (kind === "technical") return "serious";
  if (kind === "reflection") return "thinking";
  return "calm";
}

function getReadingComment(post: PostDetailResponse) {
  const kind = getArticleKind(post);
  if (kind === "technical") return "看起来很认真呢……我 quietly 在旁边陪着。";
  if (kind === "reflection") return "原来你也在想这些啊。";
  return "慢慢看，不着急。";
}

function getArticleKind(post: PostDetailResponse): ArticleKind {
  const text = [
    post.title,
    post.description,
    post.type,
    ...(post.tags ?? []),
  ]
    .join(" ")
    .toLowerCase();

  if (
    includesAny(text, [
      "技术",
      "编程",
      "开发",
      "后端",
      "前端",
      "架构",
      "java",
      "spring",
      "react",
      "next",
      "typescript",
      "redis",
      "mysql",
      "elasticsearch",
    ])
  ) {
    return "technical";
  }

  if (
    includesAny(text, [
      "观后感",
      "杂谈",
      "随便聊聊",
      "动画",
      "动漫",
      "番剧",
      "治愈",
      "日常",
      "感想",
    ])
  ) {
    return "reflection";
  }

  return "default";
}

function includesAny(text: string, keywords: string[]) {
  return keywords.some((keyword) => text.includes(keyword));
}

function getCurrentTimeOfDay(): ChthollyIllustrationProps["timeOfDay"] {
  const hour = new Date().getHours();
  if (hour >= 6 && hour < 12) return "morning";
  if (hour >= 12 && hour < 18) return "afternoon";
  if (hour >= 18 && hour < 21) return "evening";
  if (hour >= 21 || hour < 1) return "night";
  return "late-night";
}
