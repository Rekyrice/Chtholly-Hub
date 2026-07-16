import Image from "next/image";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Clock, Tag } from "lucide-react";
import MarkdownContent from "@/components/site/MarkdownContent";
import CommentSection from "@/components/site/CommentSection";
import ArticleActions from "@/components/site/ArticleActions";
import AuthorCard from "@/components/site/AuthorCard";
import ReadingProgress from "@/components/site/ReadingProgress";
import RelatedPosts from "@/components/site/RelatedPosts";
import PostQnA from "@/components/site/PostQnA";
import ArticleReadingSidebar from "@/components/site/ArticleReadingSidebar";
import type {
  ChthollyIllustrationProps,
  IllustrationState,
} from "@/components/site/ChthollyIllustration";
import { Badge } from "@/components/ui/Badge";
import { postService } from "@/lib/services/postService";
import type { PostDetailResponse } from "@/lib/types/post";
import { formatDate } from "@/lib/utils";
import { extractMarkdownHeadings } from "@/lib/markdownHeadings";
import { countWritingStats } from "@/lib/utils/markdownInsert";

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
  const headings = extractMarkdownHeadings(markdown);
  const { readingMinutes } = countWritingStats(markdown);

  return (
    <div className="article-detail-layout">
      <ReadingProgress />
      <main className="article-main">
        <article className="post-card article-detail-card">
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
              <span className="mr-4">
                {post.authorHandle ? (
                  <Link href={`/user/${encodeURIComponent(post.authorHandle)}`}>{post.authorNickname}</Link>
                ) : (
                  post.authorNickname
                )}
              </span>
            </div>
            <ArticleActions
              postId={post.id}
              slug={post.slug}
              title={post.title}
              initialLiked={post.liked}
              initialFaved={post.faved}
              initialLikeCount={post.likeCount}
              initialFavCount={post.favoriteCount}
            />
          </div>

          <ArticleReadingSidebar
            compact
            headings={headings}
            readingMinutes={readingMinutes}
            authorId={post.authorId}
            authorHandle={post.authorHandle}
            authorNickname={post.authorNickname}
            tags={post.tags}
            askHref={askHref}
            readingComment={readingComment}
            readingState={readingState}
            timeOfDay={timeOfDay}
          />

          <MarkdownContent content={markdown} />

          {post.tags.length > 0 && (
            <div className="article-detail-tags">
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
        <AuthorCard
          authorId={post.authorId}
          authorHandle={post.authorHandle}
          avatar={post.authorAvatar}
          nickname={post.authorNickname}
          bio={post.authorBio}
          postId={post.id}
          postTitle={post.title}
          postTop={post.isTop}
          postVisibility={post.visible}
        />
        <RelatedPosts postId={post.id} />
        <PostQnA postId={post.id} />
        <CommentSection postId={post.id} />
      </main>

      <ArticleReadingSidebar
        headings={headings}
        readingMinutes={readingMinutes}
        authorId={post.authorId}
        authorHandle={post.authorHandle}
        authorNickname={post.authorNickname}
        tags={post.tags}
        askHref={askHref}
        readingComment={readingComment}
        readingState={readingState}
        timeOfDay={timeOfDay}
      />
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
