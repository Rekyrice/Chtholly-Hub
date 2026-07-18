"use client";

import { useEffect, useState, type ReactNode } from "react";
import type { MarkdownHeading } from "@/lib/markdownHeadings";

type ArticleReadingNavigatorProps = {
  headings: MarkdownHeading[];
  clues: string[];
  readingSummary?: ReactNode;
};

const ACTIVE_HEADING_OFFSET = 112;

export default function ArticleReadingNavigator({
  headings,
  clues,
  readingSummary,
}: ArticleReadingNavigatorProps) {
  const [progress, setProgress] = useState(0);
  const [activeHeadingId, setActiveHeadingId] = useState(headings[0]?.id);

  useEffect(() => {
    let frameId: number | null = null;

    const updateReadingState = () => {
      frameId = null;
      const articleBody = document.querySelector<HTMLElement>("[data-article-body]");
      if (articleBody) {
        const rect = articleBody.getBoundingClientRect();
        const bodyHeight = Math.max(articleBody.scrollHeight, rect.height);
        const scrollableDistance = bodyHeight - window.innerHeight;
        const nextProgress = bodyHeight <= 0
          ? 0
          : scrollableDistance > 0
            ? (-rect.top / scrollableDistance) * 100
            : ((window.innerHeight - rect.top) / bodyHeight) * 100;
        setProgress(Math.min(100, Math.max(0, Math.round(nextProgress))));
      } else {
        setProgress(0);
      }

      if (headings.length > 0) {
        let nextActiveId = headings[0].id;
        for (const heading of headings) {
          const element = document.getElementById(heading.id);
          if (element && element.getBoundingClientRect().top <= ACTIVE_HEADING_OFFSET) {
            nextActiveId = heading.id;
          }
        }
        setActiveHeadingId(nextActiveId);
      }
    };

    const scheduleUpdate = () => {
      if (frameId !== null) return;
      frameId = window.requestAnimationFrame(updateReadingState);
    };

    scheduleUpdate();
    window.addEventListener("scroll", scheduleUpdate);
    window.addEventListener("resize", scheduleUpdate);

    return () => {
      window.removeEventListener("scroll", scheduleUpdate);
      window.removeEventListener("resize", scheduleUpdate);
      if (frameId !== null) {
        window.cancelAnimationFrame(frameId);
      }
    };
  }, [headings]);

  return (
    <div className="article-reading-navigator">
      <section className="article-reading-navigator__progress-block">
        {readingSummary}
      <div
        className="article-reading-navigator__progress"
        role="progressbar"
        aria-label="正文阅读进度"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={progress}
      >
        <span>阅读进度</span>
        <strong>{progress}%</strong>
        <span className="article-reading-navigator__track" aria-hidden="true">
          <span style={{ width: `${progress}%` }} />
        </span>
      </div>
      </section>

      {headings.length > 0 ? (
        <nav className="article-reading-navigator__toc" aria-label="本文目录">
          <div className="article-reading-sidebar__title">本文目录</div>
          <ol>
            {headings.map((heading) => (
              <li
                className={heading.level === 3 ? "article-reading-sidebar__toc-child" : undefined}
                key={heading.id}
              >
                <a
                  href={`#${heading.id}`}
                  aria-current={activeHeadingId === heading.id ? "location" : undefined}
                >
                  {heading.text}
                </a>
              </li>
            ))}
          </ol>
        </nav>
      ) : (
        <section className="article-reading-navigator__clues" aria-labelledby="reading-clues-title">
          <div className="article-reading-sidebar__title" id="reading-clues-title">阅读线索</div>
          <ul>
            {(clues.length > 0
              ? clues
              : ["这篇短文没有分节，可以从开头顺着读到结尾。"]
            ).map((clue) => <li key={clue}>{clue}</li>)}
          </ul>
        </section>
      )}
    </div>
  );
}
