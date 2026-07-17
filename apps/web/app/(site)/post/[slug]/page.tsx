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
import { loadRelatedPostCards } from "@/lib/relatedPosts";
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

  const [markdown, relatedPosts] = await Promise.all([
    loadMarkdown(post.contentUrl),
    loadRelatedPostCards(post.id),
  ]);

  const cover = post.images?.[0];
  const readingState = getArticleReadingState(post);
  const readingComment = getReadingComment(post);
  const timeOfDay = getCurrentTimeOfDay();
  const askHref = `/agent?context=${encodeURIComponent(`post:${post.slug}`)}`;
  const headings = extractMarkdownHeadings(markdown);
  const { readingMinutes, charCount } = countWritingStats(markdown);
  const clues = buildReadingClues(post.description, markdown);

  return (
    <div className="article-detail-layout">
      <ReadingProgress />
      <div className="article-primary article-main">
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
            charCount={charCount}
            publishTime={post.publishTime}
            clues={clues}
            authorId={post.authorId}
            authorHandle={post.authorHandle}
            authorNickname={post.authorNickname}
            tags={post.tags}
            relatedPosts={relatedPosts}
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
      </div>

      <ArticleReadingSidebar
        headings={headings}
        readingMinutes={readingMinutes}
        charCount={charCount}
        publishTime={post.publishTime}
        clues={clues}
        authorId={post.authorId}
        authorHandle={post.authorHandle}
        authorNickname={post.authorNickname}
        tags={post.tags}
        relatedPosts={relatedPosts}
        askHref={askHref}
        readingComment={readingComment}
        readingState={readingState}
        timeOfDay={timeOfDay}
      />

      <div className="article-followup article-main">
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
        <RelatedPosts cards={relatedPosts} />
        <PostQnA postId={post.id} />
        <CommentSection postId={post.id} />
      </div>
    </div>
  );
}

async function loadMarkdown(contentUrl: string) {
  try {
    const response = await fetch(contentUrl, { next: { revalidate: 300 } });
    if (!response.ok) {
      throw new Error(`无法加载正文：${response.status}`);
    }
    return await response.text();
  } catch {
    return "*正文暂时无法加载，请稍后再试。*";
  }
}

function getArticleReadingState(post: PostDetailResponse): IllustrationState {
  const kind = getArticleKind(post);
  if (kind === "technical") return "serious";
  if (kind === "reflection") return "thinking";
  return "calm";
}

function getReadingComment(post: PostDetailResponse) {
  const kind = getArticleKind(post);
  if (kind === "technical") return "看起来很认真呢……我会安静地在旁边陪着。";
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

export function buildReadingClues(description: string, markdown: string) {
  const markdownLines = markdown
    .replace(/```[\s\S]*?```|~~~[\s\S]*?~~~/g, " ")
    .split(/\r?\n/);
  const hasBodyLine = markdownLines.some(
    (line) =>
      line.trim() &&
      !isAtxHeading(line) &&
      !/^\s*!\[[^\]]*\]\([^)]*\)\s*$/.test(line),
  );
  const prose = markdownLines
    .filter((line) => !hasBodyLine || !isAtxHeading(line))
    .filter((line) => !/^\s*!\[[^\]]*\]\([^)]*\)\s*$/.test(line))
    .join("\n");
  const candidates = [
    ...buildReadingClueCandidates(description),
    ...buildReadingClueCandidates(prose),
  ];
  const clues: string[] = [];

  for (const plain of candidates) {
    const clipped = plain.length > 72 ? `${plain.slice(0, 71)}…` : plain;
    if (!clipped || clues.includes(clipped)) continue;
    clues.push(clipped);
    if (clues.length === 3) break;
  }

  return clues;
}

function isAtxHeading(line: string) {
  return /^\s{0,3}#{1,6}(?:[ \t]+|$)/.test(line);
}

function stripMarkdownBlockMarkers(line: string) {
  let stripped = line;

  while (true) {
    const next = stripped
      .replace(/^\s{0,3}#{1,6}(?:[ \t]+|$)/, "")
      .replace(/^\s{0,3}>[ \t]?/, "")
      .replace(/^\s{0,3}(?:[-+*]|\d+[.)])[ \t]+/, "");

    if (next === stripped) return stripped;
    stripped = next;
  }
}

function buildReadingClueCandidates(value: string) {
  return value
    .split(/\r?\n/)
    .flatMap((line) => {
      const normalized = normalizeReadingClueLine(line);
      return normalized
        ? normalized
            .split(/(?<=[。！？!?])|(?<=\.)\s+/u)
            .map((candidate) => candidate.trim())
            .filter(Boolean)
        : [];
    });
}

function normalizeReadingClueLine(value: string) {
  return stripMarkdownBlockMarkers(value)
    .replace(/!\[[^\]]*\]\([^)]*\)/g, " ")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .replace(/`([^`]+)`/g, "$1")
    .replace(/<[^>]+>/g, " ")
    .replace(/[*_~]/g, "")
    .replace(/\s+/g, " ")
    .replace(/([\p{Script=Han}])\s+(?=[\p{Script=Han}])/gu, "$1")
    .trim();
}
