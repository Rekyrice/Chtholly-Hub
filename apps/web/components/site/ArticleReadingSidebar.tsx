import Link from "next/link";
import { BookOpen, Clock3, PenLine, Tag } from "lucide-react";
import {
  ChthollyIllustration,
  type ChthollyIllustrationProps,
  type IllustrationState,
} from "@/components/site/ChthollyIllustration";
import type { MarkdownHeading } from "@/lib/markdownHeadings";
import { cn } from "@/lib/utils";

type ArticleReadingSidebarProps = {
  headings: MarkdownHeading[];
  readingMinutes: number;
  authorId?: string;
  authorNickname: string;
  tags: string[];
  askHref: string;
  readingComment: string;
  readingState: IllustrationState;
  timeOfDay: ChthollyIllustrationProps["timeOfDay"];
  compact?: boolean;
};

export default function ArticleReadingSidebar({
  headings,
  readingMinutes,
  authorId,
  authorNickname,
  tags,
  askHref,
  readingComment,
  readingState,
  timeOfDay,
  compact = false,
}: ArticleReadingSidebarProps) {
  const authorHref = authorId ? `/hub?ownerId=${encodeURIComponent(authorId)}` : "/hub";

  return (
    <aside
      className={cn("article-reading-sidebar", compact && "article-reading-sidebar--compact")}
      aria-label="文章阅读辅助"
    >
      {!compact && headings.length > 0 && (
        <nav className="article-reading-sidebar__toc" aria-label="本文目录">
          <div className="article-reading-sidebar__title"><BookOpen size={16} />本文目录</div>
          <ol>
            {headings.map((heading) => (
              <li className={heading.level === 3 ? "article-reading-sidebar__toc-child" : undefined} key={heading.id}>
                <a href={`#${heading.id}`}>{heading.text}</a>
              </li>
            ))}
          </ol>
        </nav>
      )}

      <div className="article-reading-sidebar__meta">
        <span><Clock3 size={15} />约 {readingMinutes} 分钟阅读</span>
        <Link href={authorHref}><PenLine size={15} />{authorNickname}</Link>
      </div>

      {tags.length > 0 && (
        <div className="article-reading-sidebar__tags" aria-label="文章标签">
          <Tag size={14} aria-hidden="true" />
          <div>
            {tags.map((tag) => (
              <Link href={`/tag/${encodeURIComponent(tag)}`} key={tag}>{tag}</Link>
            ))}
          </div>
        </div>
      )}

      {!compact && (
        <div className="article-reading-sidebar__companion">
          <ChthollyIllustration size="sm" state={readingState} mood={0} timeOfDay={timeOfDay} />
          <p>{readingComment}</p>
          <Link href={askHref}>问珂朵莉</Link>
        </div>
      )}
    </aside>
  );
}
