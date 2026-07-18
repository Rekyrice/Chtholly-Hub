import Link from "next/link";
import { CalendarDays, Clock3, FileText, PenLine, Sparkles, Tag } from "lucide-react";
import ArticleReadingNavigator from "@/components/site/ArticleReadingNavigator";
import {
  ChthollyIllustration,
  type ChthollyIllustrationProps,
  type IllustrationState,
} from "@/components/site/ChthollyIllustration";
import type { MarkdownHeading } from "@/lib/markdownHeadings";
import type { RelatedPostCardModel } from "@/lib/relatedPosts";
import { formatDate } from "@/lib/utils";

type ArticleReadingSidebarProps = {
  headings: MarkdownHeading[];
  readingMinutes: number;
  charCount: number;
  publishTime?: string;
  clues: string[];
  authorId?: string;
  authorHandle?: string;
  authorNickname: string;
  tags: string[];
  relatedPosts: RelatedPostCardModel[];
  askHref: string;
  readingComment: string;
  readingState: IllustrationState;
  timeOfDay: ChthollyIllustrationProps["timeOfDay"];
  compact?: boolean;
};

export default function ArticleReadingSidebar({
  headings,
  readingMinutes,
  charCount,
  publishTime,
  clues,
  authorHandle,
  authorNickname,
  tags,
  relatedPosts,
  askHref,
  readingComment,
  readingState,
  timeOfDay,
  compact = false,
}: ArticleReadingSidebarProps) {
  const authorHref = authorHandle ? `/user/${encodeURIComponent(authorHandle)}` : null;
  const author = authorHref ? (
    <Link href={authorHref}><PenLine size={15} />{authorNickname}</Link>
  ) : (
    <span><PenLine size={15} />{authorNickname}</span>
  );
  const tagLinks = tags.length > 0 && (
    <div className="article-reading-sidebar__tags" aria-label="文章标签">
      <Tag size={14} aria-hidden="true" />
      <div>
        {tags.map((tag) => (
          <Link href={`/tag/${encodeURIComponent(tag)}`} key={tag}>{tag}</Link>
        ))}
      </div>
    </div>
  );

  if (compact) {
    return (
      <aside
        className="article-reading-sidebar article-reading-sidebar--compact"
        aria-label="文章阅读辅助"
      >
        <div className="article-reading-sidebar__compact-meta">
          <span><Clock3 size={15} />约 {readingMinutes} 分钟</span>
          {author}
        </div>
        {tagLinks}
      </aside>
    );
  }

  return (
    <aside className="article-reading-sidebar" aria-label="文章阅读辅助">
      <ArticleReadingNavigator
        headings={headings}
        clues={clues}
        readingSummary={(
          <>
            <div className="article-reading-sidebar__title">
              <Clock3 size={16} />阅读轨迹
            </div>
            <div className="article-reading-sidebar__reading-meta">
              <span><Clock3 size={15} />约 {readingMinutes} 分钟</span>
              <span><FileText size={15} />{charCount.toLocaleString("zh-CN")} 字</span>
              {publishTime && (
                <time dateTime={publishTime}>
                  <CalendarDays size={15} />{formatDate(publishTime)}
                </time>
              )}
            </div>
          </>
        )}
      />

      <section className="article-reading-sidebar__section article-reading-sidebar__article-clues">
        <div className="article-reading-sidebar__title">
          <Sparkles size={16} />文章线索
        </div>
        <div className="article-reading-sidebar__author">{author}</div>
        {tagLinks}
        {relatedPosts.length > 0 && (
          <div className="article-reading-sidebar__related" aria-label="相关文章">
            {relatedPosts.slice(0, 2).map((post) => (
              <SidebarRelatedPost key={post.id} post={post} />
            ))}
          </div>
        )}
      </section>

      <section className="article-reading-sidebar__section article-reading-sidebar__companion">
        <div className="article-reading-sidebar__title">珂朵莉陪读</div>
        <ChthollyIllustration size="sm" state={readingState} mood={0} timeOfDay={timeOfDay} />
        <p>{readingComment}</p>
        <Link href={askHref}>问珂朵莉</Link>
      </section>
    </aside>
  );
}

function SidebarRelatedPost({ post }: { post: RelatedPostCardModel }) {
  const content = (
    <>
      <strong>{post.title}</strong>
      {(post.summary || post.description) && <span>{post.summary || post.description}</span>}
    </>
  );

  if (post.href) {
    return <Link className="article-reading-sidebar__related-card" href={post.href}>{content}</Link>;
  }

  return (
    <article className="article-reading-sidebar__related-card article-reading-sidebar__related-card--static">
      {content}
    </article>
  );
}
